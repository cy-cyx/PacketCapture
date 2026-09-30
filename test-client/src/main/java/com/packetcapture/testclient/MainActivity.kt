package com.packetcapture.testclient

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.*
import javax.net.ssl.*

/** 独立测试应用：不绕过 VPN，也不禁用主机名校验。CA 导入和自动化入口仅在 debug 构建启用。 */
class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var log: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val running = java.util.concurrent.atomic.AtomicBoolean()
    private val caFile get() = File(filesDir, "capture-ca.cer")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 24) }
        column.addView(TextView(this).apply { text = "抓包测试客户端"; textSize = 24f })
        column.addView(TextView(this).apply { text = "输入同一网络内测试服务器的 IPv4 地址。请求使用 fixture.packet.test 作为 SNI，经真实 VPN 转发。" })
        address = EditText(this).apply { hint = "测试服务器 IPv4"; setText(getPreferences(0).getString("server", "192.168.21.1")); setSingleLine() }
        column.addView(address)
        column.addView(Button(this).apply { text = "导入抓包 CA（调试）"; setOnClickListener {
            if (BuildConfig.DEBUG) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 42)
        } })
        column.addView(Button(this).apply { text = "运行功能测试"; setOnClickListener { runSuite(address.text.toString().trim(), 1, 1) } })
        column.addView(Button(this).apply { text = "50 并发 · 10,000 请求"; setOnClickListener { runSuite(address.text.toString().trim(), 10000, 50) } })
        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true); text = "等待测试\n" }
        column.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
        handleIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent) {
        if (BuildConfig.DEBUG && intent.getBooleanExtra("proxySmoke", false)) {
            runProxySmoke(intent.getBooleanExtra("trustCapture", true)); return
        }
        if (BuildConfig.DEBUG && intent.hasExtra("server")) {
            val server = intent.getStringExtra("server")!!; address.setText(server)
            runSuite(server, intent.getIntExtra("count", 1), intent.getIntExtra("parallel", 1), intent.getLongExtra("durationSeconds", 0), intent.getBooleanExtra("extended", false))
        }
    }
    @Deprecated("Activity callback") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK && BuildConfig.DEBUG) {
            try { contentResolver.openInputStream(data!!.data!!)!!.use { input ->
                val bytes = input.readBytes(); CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream())
                caFile.writeBytes(bytes)
            }; append("CA 已导入；后续连接将使用此证书") } catch (e: Exception) { append("导入失败: ${e.message}") }
        }
    }
    private fun append(text: String) { android.util.Log.i("CaptureTest", text); runOnUiThread { log.append("$text\n"); if (log.length() > 24000) log.text = log.text.takeLast(18000) } }
    private fun client(server: String, trustCapture: Boolean = true): OkHttpClient {
        val builder = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = if (hostname.endsWith("packet.test")) listOf(InetAddress.getByName(server)) else Dns.SYSTEM.lookup(hostname)
        })
            .callTimeout(20, TimeUnit.SECONDS).connectionPool(ConnectionPool(50, 2, TimeUnit.MINUTES))
        if (BuildConfig.DEBUG && trustCapture && caFile.exists()) {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null)
                caFile.inputStream().use { setCertificateEntry("capture", CertificateFactory.getInstance("X.509").generateCertificate(it)) }
                val fixtureId = resources.getIdentifier("fixture_ca", "raw", packageName)
                if (fixtureId != 0) resources.openRawResource(fixtureId).use { setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(it)) }
            }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            builder.sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }.socketFactory, manager)
        }
        return builder.build()
    }
    /** 实机代理链路：系统 DNS、UDP DNS、原始 TCP、HTTP、HTTPS 和并发 HTTP/2。 */
    private fun runProxySmoke(trustCapture: Boolean) {
        if (!running.compareAndSet(false, true)) return
        startForegroundService(Intent(this, TestRunService::class.java))
        executor.execute {
            val results = java.util.Collections.synchronizedList(mutableListOf<String>())
            val http = client("127.0.0.1", trustCapture).newBuilder().dns(Dns.SYSTEM).build()
            fun check(label: String, block: () -> String) {
                val line = try { "PASS $label ${block()}" } catch (e: Exception) { "FAIL $label ${e.javaClass.simpleName}: ${e.message}" }
                results += line; append(line)
            }
            try {
                File(filesDir, "proxy-results.txt").writeText("RUNNING\n")
                val query = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3, 119, 119, 119, 6, 103, 111, 111, 103, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
                fun verifyDns(reply: ByteArray) {
                    check(reply.size >= 12 && reply[0] == query[0] && reply[1] == query[1] && reply[3].toInt() and 15 == 0 && (reply[6].toInt() != 0 || reply[7].toInt() != 0)) { "DNS 回复无效" }
                }
                check("UDP_DNS") {
                    java.net.DatagramSocket().use { socket ->
                        socket.soTimeout = 10000
                        socket.send(java.net.DatagramPacket(query, query.size, InetAddress.getByName("1.1.1.1"), 53))
                        val response = java.net.DatagramPacket(ByteArray(4096), 4096); socket.receive(response)
                        verifyDns(response.data.copyOf(response.length)); "${response.length} bytes"
                    }
                }
                check("RAW_TCP_DNS") {
                    java.net.Socket().use { socket ->
                        socket.soTimeout = 10000; socket.connect(java.net.InetSocketAddress("1.1.1.1", 53), 10000)
                        java.io.DataOutputStream(socket.getOutputStream()).apply { writeShort(query.size); write(query); flush() }
                        val input = java.io.DataInputStream(socket.getInputStream())
                        val reply = ByteArray(input.readUnsignedShort()).also(input::readFully); verifyDns(reply); "${reply.size} bytes"
                    }
                }
                check("SYSTEM_DNS") { InetAddress.getAllByName("www.google.com").joinToString { it.hostAddress.orEmpty() } }
                fun request(url: String, code: Int, bodyMarker: String? = null): String = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    check(response.code == code && (bodyMarker == null || body.contains(bodyMarker))) { "HTTP ${response.code}, body=${body.take(80)}" }
                    "${response.protocol} ${response.code} body=${body.length}"
                }
                check("HTTP") { request("http://www.gstatic.com/generate_204", 204) }
                check("HTTPS_BODY") { request("https://www.google.com/robots.txt", 200, "User-agent") }
                val pool = Executors.newFixedThreadPool(4)
                try { (1..8).map { n -> pool.submit { check("HTTPS_CONCURRENT_$n") { request("https://www.gstatic.com/generate_204?proxy_check=$n", 204) } } }.forEach { it.get() } }
                finally { pool.shutdownNow() }
            } catch (e: Exception) { results += "FAIL SUITE ${e.message}" }
            finally {
                val report = "FINISHED total=${results.size} passed=${results.count { it.startsWith("PASS") }} failed=${results.count { it.startsWith("FAIL") }}\n" + results.joinToString("\n")
                File(filesDir, "proxy-results.txt").writeText(report); append(report.lineSequence().first())
                http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
                running.set(false); stopService(Intent(this, TestRunService::class.java))
            }
        }
    }
    private fun runSuite(server: String, count: Int, parallel: Int, durationSeconds: Long = 0, extended: Boolean = false) {
        if (!running.compareAndSet(false, true)) { append("已有测试正在运行，请等待本轮结束"); return }
        getPreferences(0).edit().putString("server", server).apply()
        startForegroundService(Intent(this, TestRunService::class.java))
        executor.execute {
            try {
            File(filesDir, "last-results.txt").writeText("RUNNING count=$count parallel=$parallel durationSeconds=$durationSeconds\n")
            val client = client(server)
            val results = java.util.Collections.synchronizedList(mutableListOf<String>())
            fun check(label: String, request: Request, expected: (Response, String) -> Boolean = { response, _ -> response.isSuccessful }) {
                try { client.newCall(request).execute().use { response -> val body = response.body?.string().orEmpty()
                    val passed = expected(response, body)
                    val line = "${if (passed) "PASS" else "FAIL"} $label ${response.protocol} ${response.code} ${body.take(160)}"
                    results += line; if (count == 1 || !passed) append(line)
                } } catch (e: Exception) { val line = "FAIL $label ${e.javaClass.simpleName}: ${e.message}"; results += line; append(line) }
            }
            if (count == 1) {
                for (scheme in listOf("http://fixture.packet.test:8080", "https://fixture.packet.test:8443")) {
                    check("GET $scheme", Request.Builder().url("$scheme/get?marker=hello").addHeader("X-Repeat", "a").addHeader("X-Repeat", "b").build()) { r,b -> r.code == 200 && b.contains("hello") }
                    check("JSON POST", Request.Builder().url("$scheme/echo").post("{\"marker\":\"中文测试\"}".toRequestBody("application/json".toMediaType())).build()) { _,b -> b.contains("中文测试") }
                    check("FORM", Request.Builder().url("$scheme/echo").post(FormBody.Builder().add("name", "a & b").build()).build()) { _,b -> b.contains("name=") }
                    check("UPLOAD", Request.Builder().url("$scheme/upload").post(MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "sample.bin", ByteArray(32768) { (it % 251).toByte() }.toRequestBody()).build()).build())
                    check("REDIRECT", Request.Builder().url("$scheme/redirect").build()) { _,b -> b.contains("redirected") }
                    check("CHUNKED", Request.Builder().url("$scheme/chunked").build()) { _,b -> b == "chunk-one|chunk-two|" }
                    check("GZIP", Request.Builder().url("$scheme/gzip").build()) { _,b -> b.contains("compressed") }
                    check("HEAD", Request.Builder().url("$scheme/get").head().build()) { r,b -> r.code == 200 && b.isEmpty() }
                    check("204", Request.Builder().url("$scheme/empty").build()) { r,b -> r.code == 204 && b.isEmpty() }
                }
                val pool = Executors.newFixedThreadPool(12)
                (1..50).map { n -> pool.submit { check("H2 stream $n", Request.Builder().url("https://fixture.packet.test:8443/get?marker=stream-$n").build()) { r,b -> r.protocol == Protocol.HTTP_2 && b.contains("\"marker\": \"stream-$n\"") } } }.forEach { it.get() }; pool.shutdown()
                val cancel = client.newCall(Request.Builder().url("https://fixture.packet.test:8443/slow").build())
                cancel.enqueue(object : Callback { override fun onFailure(call: Call,e: java.io.IOException) { append("${if (call.isCanceled()) "PASS" else "FAIL"} CANCEL ${e.message}") }; override fun onResponse(call: Call,response: Response) { response.close(); append("FAIL CANCEL response") } })
                Thread.sleep(100); cancel.cancel()
                try { client(server, false).newCall(Request.Builder().url("https://fixture.packet.test:8443/get").build()).execute().use { append("INFO 未信任测试返回 ${it.code}（未启用解密时属于正常）") } }
                catch (e: Exception) { append("PASS CA_REJECT ${e.javaClass.simpleName}") }
                try { client.newCall(Request.Builder().url("https://fixture.packet.test:8444/get").build()).execute().use { append("FAIL BAD_UPSTREAM_CERT ${it.code}") } }
                catch (e: Exception) { append("PASS BAD_UPSTREAM_CERT ${e.javaClass.simpleName}") }
                if (extended) {
                    check("LARGE 6 MiB", Request.Builder().url("https://fixture.packet.test:8443/large").build()) { r,b -> r.isSuccessful && b.length == 6*1024*1024 && b.all { it == 'X' } }
                    check("LARGE UPLOAD 6 MiB", Request.Builder().url("https://fixture.packet.test:8443/upload").post(ByteArray(6*1024*1024) { (it%251).toByte() }.toRequestBody()).build()) { r,b -> r.isSuccessful && b.contains("6291456") }
                    try {
                        java.net.DatagramSocket().use { socket ->
                            socket.soTimeout = 3000
                            val data = "udp-vpn-echo".toByteArray()
                            socket.send(java.net.DatagramPacket(data, data.size, InetAddress.getByName(server), 9999))
                            val reply = java.net.DatagramPacket(ByteArray(1024), 1024); socket.receive(reply)
                            val line = "${if (reply.data.copyOf(reply.length).contentEquals(data)) "PASS" else "FAIL"} UDP echo"
                            results += line; append(line)
                        }
                    } catch (e: Exception) { val line = "FAIL UDP ${e.message}"; results += line; append(line) }
                    try { client.newBuilder().callTimeout(500, TimeUnit.MILLISECONDS).build().newCall(Request.Builder().url("https://fixture.packet.test:8443/slow").build()).execute().use { results += "FAIL TIMEOUT"; append("FAIL TIMEOUT") } }
                    catch (e: java.io.InterruptedIOException) { results += "PASS TIMEOUT"; append("PASS TIMEOUT") }
                }
            } else {
                val pool = Executors.newFixedThreadPool(parallel.coerceIn(1, 50))
                val start = System.nanoTime()
                val sequence = java.util.concurrent.atomic.AtomicInteger()
                val jobs = (1..parallel.coerceIn(1, 50)).map { pool.submit {
                    while (true) {
                        val n = sequence.getAndIncrement(); if (n >= count.coerceAtMost(100000)) break
                        if (durationSeconds > 0) {
                            // 每批同时释放 parallel 个请求，兼顾 30 分钟持续运行与 50 并发峰值。
                            val scheduled = start + (n / parallel).toLong() * parallel * durationSeconds * 1000000000 / count
                            val wait = scheduled - System.nanoTime(); if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait)
                        }
                        check("stress-$n", Request.Builder().url("https://fixture.packet.test:8443/get?marker=stress-$n").build()) { r,b -> r.isSuccessful && b.contains("\"marker\": \"stress-$n\"") }
                        if (n % 500 == 0) append("PROGRESS $n / $count")
                    }
                } }; jobs.forEach { it.get() }; pool.shutdown()
                val remaining = start + durationSeconds * 1000000000 - System.nanoTime()
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
            }
            val report = "FINISHED total=${results.size} passed=${results.count { it.startsWith("PASS") }} failed=${results.count { it.startsWith("FAIL") }}\n" + results.joinToString("\n")
            File(filesDir, "last-results.txt").writeText(report)
            append(report.lineSequence().first())
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            } catch (e: Exception) {
                val failure = "FAIL TEST_RUN ${e.javaClass.simpleName}: ${e.message}"
                File(filesDir, "last-results.txt").writeText(failure); append(failure)
            }
            finally { running.set(false); stopService(Intent(this, TestRunService::class.java)) }
        }
    }
}
