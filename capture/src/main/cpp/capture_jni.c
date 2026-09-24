#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <poll.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <android/multinetwork.h>
#include "zdtun.h"

/* JNIEnv 只属于 run 的工作线程，所有 zdtun 回调必须在该线程执行。 */
typedef struct {
    JNIEnv *env; jobject bridge;
    jmethodID protect, opened, mapped, closed, traffic;
    int tun_fd, wake_fd, fatal, upstream_proxy;
    uint64_t network;
    int64_t next_id, uploaded, downloaded;
} engine_t;
typedef struct { int64_t id; int mapped; } connection_t;
static engine_t *engine(zdtun_t *tun) { return zdtun_userdata(tun); }
static void check_java(engine_t *ctx) {
    if ((*ctx->env)->ExceptionCheck(ctx->env)) {
        (*ctx->env)->ExceptionDescribe(ctx->env);
        (*ctx->env)->ExceptionClear(ctx->env); ctx->fatal = 1;
    }
}
static int protect_socket(zdtun_t *tun, socket_t fd) {
    engine_t *ctx = engine(tun);
    jboolean success = (*ctx->env)->CallBooleanMethod(ctx->env, ctx->bridge, ctx->protect, (jint)fd);
    check_java(ctx);
    if (!success || ctx->fatal) return 1;
    int type = 0; socklen_t len = sizeof(type);
    getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len);
    /* 代理模式的 TCP / UDP 都只连接本机；直连 UDP 才绑定底层网络。 */
    if (type == SOCK_DGRAM && !ctx->upstream_proxy && ctx->network && android_setsocknetwork(ctx->network, fd) != 0) return 1;
    return 0;
}
static int send_client(zdtun_t *tun, zdtun_pkt_t *packet, const zdtun_conn_t *connection) {
    (void)connection;
    engine_t *ctx = engine(tun);
    ssize_t n = write(ctx->tun_fd, packet->buf, packet->len);
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        struct pollfd ready = {.fd = ctx->tun_fd, .events = POLLOUT};
        if (poll(&ready, 1, 100) > 0) n = write(ctx->tun_fd, packet->buf, packet->len);
    }
    if (n != packet->len) { ctx->fatal = 1; return -1; }
    return 0;
}
static void account(zdtun_t *tun, const zdtun_pkt_t *packet, uint8_t to_tun, const zdtun_conn_t *conn) {
    (void)conn;
    engine_t *ctx = engine(tun);
    if (to_tun) ctx->uploaded += packet->len; else ctx->downloaded += packet->len;
}
static void closed(zdtun_t *tun, const zdtun_conn_t *conn);
static int opened(zdtun_t *tun, zdtun_conn_t *conn) {
    engine_t *ctx = engine(tun);
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn);
    connection_t *state = calloc(1, sizeof(*state));
    if (!state) return 1;
    state->id = ++ctx->next_id;
    zdtun_conn_set_userdata(conn, state);
    if (tuple->ipproto == IPPROTO_TCP) zdtun_conn_proxy(conn);
    char src_buf[INET6_ADDRSTRLEN], dst_buf[INET6_ADDRSTRLEN];
    int family = tuple->ipver == 4 ? AF_INET : AF_INET6;
    inet_ntop(family, &tuple->src_ip, src_buf, sizeof(src_buf));
    inet_ntop(family, &tuple->dst_ip, dst_buf, sizeof(dst_buf));
    jstring src = (*ctx->env)->NewStringUTF(ctx->env, src_buf);
    jstring dst = (*ctx->env)->NewStringUTF(ctx->env, dst_buf);
    jint udp_port = (*ctx->env)->CallIntMethod(ctx->env, ctx->bridge, ctx->opened, (jlong)state->id, src, (jint)ntohs(tuple->src_port),
        dst, (jint)ntohs(tuple->dst_port), (jint)tuple->ipproto);
    (*ctx->env)->DeleteLocalRef(ctx->env, src); (*ctx->env)->DeleteLocalRef(ctx->env, dst);
    check_java(ctx);
    /* zdtun 在 on_connection_open 拒绝时直接 free(conn)，不会调用 on_connection_close。 */
    if (ctx->fatal || udp_port < 0) { closed(tun, conn); return 1; }
    if (ctx->upstream_proxy && tuple->ipproto == IPPROTO_UDP) {
        if (udp_port == 0 || udp_port > 65535) { closed(tun, conn); return 1; }
        zdtun_ip_t relay; zdtun_parse_ip("127.0.0.1", &relay);
        zdtun_conn_dnat(conn, &relay, htons((uint16_t)udp_port), 4);
    }
    return 0;
}
static void closed(zdtun_t *tun, const zdtun_conn_t *conn) {
    engine_t *ctx = engine(tun);
    connection_t *state = zdtun_conn_get_userdata(conn);
    if (!state) return;
    int status = zdtun_conn_get_status(conn);
    jstring error = status >= CONN_STATUS_ERROR ? (*ctx->env)->NewStringUTF(ctx->env, zdtun_conn_status2str(status)) : NULL;
    (*ctx->env)->CallVoidMethod(ctx->env, ctx->bridge, ctx->closed, (jlong)state->id, error);
    if (error) (*ctx->env)->DeleteLocalRef(ctx->env, error);
    check_java(ctx);
    /* close 只标记连接，zdtun 稍后才回收结构体；清空 userdata，避免本轮尾部再次访问悬空指针。 */
    zdtun_conn_set_userdata((zdtun_conn_t*)conn, NULL);
    free(state);
}
static void publish_mapping(zdtun_t *tun, zdtun_conn_t *conn) {
    if (!conn || zdtun_conn_get_5tuple(conn)->ipproto != IPPROTO_TCP) return;
    connection_t *state = zdtun_conn_get_userdata(conn);
    if (!state || state->mapped) return;
    int fd = zdtun_conn_get_socket(conn);
    if (fd < 0) return;
    struct sockaddr_in local = {0}; socklen_t size = sizeof(local);
    if (getsockname(fd, (struct sockaddr*)&local, &size) == 0 && local.sin_port != 0) {
        state->mapped = 1;
        engine_t *ctx = engine(tun);
        (*ctx->env)->CallVoidMethod(ctx->env, ctx->bridge, ctx->mapped, (jlong)state->id, (jint)ntohs(local.sin_port));
        check_java(ctx);
    }
}
JNIEXPORT jint JNICALL Java_com_packetcapture_capture_engine_NativeBridge_run(
        JNIEnv *env, jobject self, jint tun_fd, jint wake_fd, jint proxy_port, jstring username, jstring password, jlong network, jboolean upstream_proxy) {
    engine_t ctx = {.env = env, .bridge = self, .tun_fd = tun_fd, .wake_fd = wake_fd, .network = (uint64_t)network, .upstream_proxy = upstream_proxy};
    if (tun_fd < 0 || wake_fd < 0 || tun_fd >= FD_SETSIZE || wake_fd >= FD_SETSIZE) {
        if (tun_fd >= 0) close(tun_fd);
        if (wake_fd >= 0) close(wake_fd);
        return -1;
    }
    jclass cls = (*env)->GetObjectClass(env, self);
    ctx.protect = (*env)->GetMethodID(env, cls, "protectSocket", "(I)Z");
    ctx.opened = (*env)->GetMethodID(env, cls, "connectionOpened", "(JLjava/lang/String;ILjava/lang/String;II)I");
    ctx.mapped = (*env)->GetMethodID(env, cls, "connectionMapped", "(JI)V");
    ctx.closed = (*env)->GetMethodID(env, cls, "connectionClosed", "(JLjava/lang/String;)V");
    ctx.traffic = (*env)->GetMethodID(env, cls, "traffic", "(JJ)V");
    (*env)->DeleteLocalRef(env, cls);
    struct zdtun_callbacks callbacks = {.send_client = send_client, .account_packet = account,
        .on_socket_open = protect_socket, .on_connection_open = opened, .on_connection_close = closed};
    zdtun_t *tun = zdtun_init(&callbacks, &ctx);
    if (!tun) { close(tun_fd); close(wake_fd); return -2; }
    zdtun_ip_t proxy; zdtun_parse_ip("127.0.0.1", &proxy);
    zdtun_set_socks5_proxy(tun, &proxy, htons((uint16_t)proxy_port), 4);
    const char *user = (*env)->GetStringUTFChars(env, username, NULL);
    const char *pass = (*env)->GetStringUTFChars(env, password, NULL);
    zdtun_set_socks5_userpass(tun, user, pass);
    (*env)->ReleaseStringUTFChars(env, username, user); (*env)->ReleaseStringUTFChars(env, password, pass);
    zdtun_set_mtu(tun, 1500);
    fcntl(tun_fd, F_SETFL, fcntl(tun_fd, F_GETFL) | O_NONBLOCK);
    char packet[65535]; time_t last_tick = 0;
    while (!ctx.fatal) {
        fd_set reads, writes; int maximum;
        zdtun_fds(tun, &maximum, &reads, &writes);
        FD_SET(tun_fd, &reads); FD_SET(wake_fd, &reads);
        if (maximum < tun_fd) maximum = tun_fd;
        if (maximum < wake_fd) maximum = wake_fd;
        struct timeval timeout = {.tv_sec = 0, .tv_usec = 250000};
        int ready = select(maximum + 1, &reads, &writes, NULL, &timeout);
        if (ready < 0) { if (errno == EINTR) continue; ctx.fatal = 1; break; }
        if (FD_ISSET(wake_fd, &reads)) break;
        int tun_ready = FD_ISSET(tun_fd, &reads);
        FD_CLR(tun_fd, &reads); FD_CLR(wake_fd, &reads);
        /* 必须先消费 select 的 socket 快照。先处理 TUN 会关闭并重用 fd，旧可读位可能落到
         * 新 socket 上，让 zdtun 的 recv 阻塞整个转发线程；本轮新建的 socket 等下一轮 select。 */
        zdtun_handle_fd(tun, &reads, &writes);
        if (tun_ready) {
            for (int count = 0; count < 32; ++count) {
                ssize_t length = read(tun_fd, packet, sizeof(packet));
                if (length <= 0) {
                    if (length == 0 || (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) ctx.fatal = 1;
                    break;
                }
                zdtun_pkt_t parsed;
                if (zdtun_parse_pkt(tun, packet, (uint16_t)length, &parsed) != 0) continue;
                zdtun_conn_t *conn = zdtun_lookup(tun, &parsed.tuple, 1);
                if (conn) { zdtun_forward(tun, &parsed, conn); publish_mapping(tun, conn); }
            }
        }
        time_t now = time(NULL);
        if (now != last_tick) {
            last_tick = now; zdtun_purge_expired(tun);
            (*env)->CallVoidMethod(env, self, ctx.traffic, (jlong)ctx.uploaded, (jlong)ctx.downloaded);
            check_java(&ctx);
        }
    }
    zdtun_finalize(tun);
    (*env)->CallVoidMethod(env, self, ctx.traffic, (jlong)ctx.uploaded, (jlong)ctx.downloaded);
    /* detach 后 fd 归本线程独占；停止只写唤醒管道，不能在另一线程抢先 close。 */
    close(tun_fd); close(wake_fd);
    return ctx.fatal ? -3 : 0;
}
