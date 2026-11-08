package com.androidcommander.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
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
    private val PICK_FOLDER = 42
    private var folderLabel: TextView? = null
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
        folderLabel = TextView(this).apply { text = "Approved folder: " + (prefs.getString("folder", null) ?: "none") }
        layout.addView(folderLabel)
        layout.addView(Button(this).apply {
            text = "Choose folder for read-only MCP access"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                startActivityForResult(intent, PICK_FOLDER)
            }
        })
        startButton = Button(this).apply { text="Connect"; setOnClickListener { toggle() } }
        status = TextView(this).apply { text="Disconnected" }
        layout.addView(startButton); layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString("folder", uri.toString()).apply()
                folderLabel?.text = "Approved folder: $uri"
            } catch (e: Exception) {
                status.text = "Folder permission failed: ${e.message}"
            }
        }
    }

    private fun resolveFile(path: String): DocumentFile? {
        val uriString = prefs.getString("folder", null) ?: return null
        val root = DocumentFile.fromTreeUri(this, Uri.parse(uriString)) ?: return null
        if (path.isEmpty()) return root
        val parts = path.split("/")
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.contains(92.toChar()) }) return null
        var current: DocumentFile = root
        for (part in parts) {
            if (!current.isDirectory) return null
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun fileOperation(operation: String, args: JSONObject): JSONObject {
        val path = args.optString("path", "")
        val file = resolveFile(path) ?: return JSONObject().put("error", "Folder not selected, permission lost, or path not found")
        if (!file.exists()) return JSONObject().put("error", "File not found")
        when (operation) {
            "list_files" -> {
                if (!file.isDirectory) return JSONObject().put("error", "Not a directory")
                val children = file.listFiles()
                val entries = org.json.JSONArray()
                for (child in children.take(100)) {
                    entries.put(JSONObject()
                        .put("name", child.name ?: "")
                        .put("is_directory", child.isDirectory)
                        .put("size", child.length())
                        .put("mime_type", child.type ?: ""))
                }
                return JSONObject().put("path", path).put("entries", entries).put("truncated", children.size > 100)
            }
            "file_info" -> {
                return JSONObject().put("path", path)
                    .put("name", file.name ?: "")
                    .put("size", file.length())
                    .put("mime_type", file.type ?: "")
                    .put("is_directory", file.isDirectory)
            }
            "read_file" -> {
                if (!file.isFile) return JSONObject().put("error", "Not a file")
                val mime = file.type ?: ""
                val name = (file.name ?: "").lowercase()
                val extensions = listOf(".txt", ".md", ".json", ".csv", ".log", ".xml", ".yaml", ".yml", ".kt", ".py", ".js", ".html", ".css")
                if (!mime.startsWith("text/") && !extensions.any { name.endsWith(it) }) {
                    return JSONObject().put("error", "Only text files are allowed")
                }
                val stream = contentResolver.openInputStream(file.uri)
                    ?: return JSONObject().put("error", "Unable to open file")
                val bytes = stream.use { input ->
                    val buffer = ByteArray(16385)
                    var count = 0
                    while (count < buffer.size) {
                        val n = input.read(buffer, count, buffer.size - count)
                        if (n <= 0) break
                        count += n
                    }
                    buffer.copyOf(count)
                }
                val truncated = bytes.size > 16384
                val textBytes = if (truncated) bytes.copyOf(16384) else bytes
                val decoder = Charsets.UTF_8.newDecoder()
                decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                val text = try { decoder.decode(java.nio.ByteBuffer.wrap(textBytes)).toString() }
                    catch (_: Exception) { return JSONObject().put("error", "Not valid UTF-8 text in first 16 KiB") }
                return JSONObject().put("path", path).put("text", text).put("truncated", truncated)
            }
        }
        return JSONObject().put("error", "Unsupported file operation")
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
                    val arguments=job.optJSONObject("arguments") ?: JSONObject()
                    main.post {
                        if (running) AlertDialog.Builder(this)
                            .setTitle("Approve device request?")
                            .setMessage("Operation: $operation\nPath: ${arguments.optString("path", "")}\nRequest ID: $id\nOnly approve requests you recognize.")
                            .setPositiveButton("Allow once") { _,_ -> execute(base,token,id,operation,arguments) }
                            .setNegativeButton("Deny") { _,_ -> submit(base,token,id,JSONObject().put("error","Denied by device user")) }
                            .setCancelable(false).show()
                    }
                } else main.post { status.text="Connected — waiting for requests" }
            } catch (e:Exception) { main.post { status.text="Connection error: ${e.message}" } }
            main.postDelayed({ poll(base,token) }, 3000)
        }
    }
    private fun execute(base:String,token:String,id:String,operation:String,args:JSONObject) {
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
            "list_files", "file_info", "read_file" -> try { fileOperation(operation, args) } catch(e:Exception) { JSONObject().put("error", "File operation failed: ${e.javaClass.simpleName}") }
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
