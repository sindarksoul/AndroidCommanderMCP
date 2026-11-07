package com.androidcommander.app

import android.app.Activity
import android.app.AlertDialog
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private lateinit var status: TextView
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var startButton: Button
    private val prefs by lazy { getSharedPreferences("commander", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,32,32,32) }
        layout.addView(TextView(this).apply { text="Android Commander MCP v0.1"; textSize=22f })
        layout.addView(TextView(this).apply { text="Connect to your own HTTPS relay. Keep this screen open for v0.1." })
        urlField = EditText(this).apply { hint="https://your-relay.example.com"; setSingleLine(true); setText(prefs.getString("url", "")) }
        tokenField = EditText(this).apply { hint="Device token"; inputType=129; setText(prefs.getString("token", "")) }
        layout.addView(urlField); layout.addView(tokenField)
        startButton = Button(this).apply { text="Connect"; setOnClickListener { toggle() } }
        status = TextView(this).apply { text="Disconnected" }
        layout.addView(startButton); layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    private fun toggle() {
        if (running) { running=false; startButton.text="Connect"; status.text="Disconnected"; return }
        val base=urlField.text.toString().trim().trimEnd('/')
        val token=tokenField.text.toString().trim()
        if (!base.startsWith("https://") || token.isBlank()) { status.text="HTTPS URL and device token required"; return }
        prefs.edit().putString("url",base).putString("token",token).apply()
        running=true; startButton.text="Disconnect"; status.text="Connecting…"
        poll(base,token)
    }
    private fun poll(base:String, token:String) {
        if (!running) return
        worker.execute {
            try {
                val response=request("GET", "$base/device/next", token, null)
                val job=JSONObject(response)
                if (job.has("id") && !job.isNull("id")) {
                    val id=job.getString("id")
                    val operation=job.getString("operation")
                    main.post {
                        if (running) AlertDialog.Builder(this)
                            .setTitle("Approve device request?")
                            .setMessage("Operation: $operation\nRequest ID: $id\nOnly approve requests you recognize.")
                            .setPositiveButton("Allow once") { _,_ -> execute(base,token,id,operation) }
                            .setNegativeButton("Deny") { _,_ -> submit(base,token,id,JSONObject().put("error","Denied by device user")) }
                            .setCancelable(false).show()
                    }
                } else main.post { status.text="Connected — waiting for requests" }
            } catch (e:Exception) { main.post { status.text="Connection error: ${e.message}" } }
            main.postDelayed({ poll(base,token) }, 3000)
        }
    }
    private fun execute(base:String,token:String,id:String,operation:String) {
        val result=when(operation) {
            "device_info" -> JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("android_version",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT)
            "battery_info" -> {
                val bm=getSystemService(BATTERY_SERVICE) as BatteryManager
                JSONObject().put("percentage",bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging",bm.isCharging)
            }
            "storage_info" -> {
                val fs=android.os.Environment.getDataDirectory()
                JSONObject().put("total_bytes",fs.totalSpace).put("free_bytes",fs.freeSpace)
            }
            else -> JSONObject().put("error","Unsupported operation")
        }
        submit(base,token,id,result)
    }
    private fun submit(base:String,token:String,id:String,result:JSONObject) {
        worker.execute { try { request("POST","$base/device/result/$id",token,result.toString()) }
            catch(e:Exception) { main.post { status.text="Result delivery failed: ${e.message}" } } }
    }
    private fun request(method:String, endpoint:String, token:String, body:String?):String {
        val c=URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.requestMethod=method; c.connectTimeout=10000; c.readTimeout=10000
            c.setRequestProperty("Authorization","Bearer $token")
            if (body!=null) { c.doOutput=true; c.setRequestProperty("Content-Type","application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    override fun onDestroy() { running=false; worker.shutdownNow(); super.onDestroy() }
}
