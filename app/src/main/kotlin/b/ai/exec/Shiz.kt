package b.ai.exec

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import b.ai.App
import b.ai.R
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import java.io.OutputStream

/** Shizuku 通道：直连 IShizukuService（移植自 Operit），不再自建 :exec 用户服务。
 *  旧实现的 bound 标志在服务重启后不重置，会卡出「用户服务连接失败」，直连方案没有这个状态可卡。 */
object Shiz {
    const val PKG = "moe.shizuku.privileged.api"
    const val REQ_CODE = 4210

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    /** 授权结果：区分「没服务」「被永久拒绝」「超时没回应」，UI 才能给对的引导 */
    enum class Perm { GRANTED, DENIED, NEVER_ASK, NO_SERVICE, ERROR }

    data class Probe(val state: State, val desc: String, val uid: Int = -1) {
        val ok: Boolean get() = state == State.READY
    }

    @Volatile
    private var cached: IShizukuService? = null

    private var permListener: Shizuku.OnRequestPermissionResultListener? = null

    @Volatile
    private var permCb: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var askSeq = 0

    /** 装了 App 不等于能用，只用来区分"没装"和"没启动" */
    fun installed(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    }.getOrDefault(false)

    fun probe(ctx: Context): Probe {
        if (!Shizuku.pingBinder()) {
            return if (installed(ctx)) {
                Probe(State.NOT_RUNNING, ctx.getString(R.string.shiz_not_run))
            } else {
                Probe(State.NOT_INSTALLED, ctx.getString(R.string.shiz_not_installed))
            }
        }
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val ver = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            return Probe(State.NO_PERMISSION, ctx.getString(R.string.shiz_old, ver), uid)
        }
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        if (granted == PackageManager.PERMISSION_GRANTED) {
            val backend = ctx.getString(if (uid == 0) R.string.shiz_back_root else R.string.shiz_back_adb)
            return Probe(State.READY, ctx.getString(R.string.shiz_ready, ver, uid, backend), uid)
        }
        return Probe(State.NO_PERMISSION, ctx.getString(R.string.shiz_no_perm), uid)
    } //真检测：binder 存活 + 服务版本 + 授权状态

    /** 直连服务接口：缓存只认 pingBinder 活着的，死了立即重建，不留脏状态 */
    private fun service(): IShizukuService? {
        cached?.let { s ->
            if (runCatching { s.asBinder().pingBinder() }.getOrDefault(false)) return s
            cached = null
        }
        return try {
            if (!Shizuku.pingBinder()) return null
            val binder = Shizuku.getBinder() ?: return null
            if (!binder.isBinderAlive) return null
            val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            if (uid != 0 && uid != 2000) return null
            IShizukuService.Stub.asInterface(binder)?.also { cached = it }
        } catch (t: Throwable) {
            null
        }
    } //IShizukuService 直连

    fun requestPermission(cb: (Boolean) -> Unit) {
        ask { cb(it == Perm.GRANTED) }
    } //旧接口：只要成败

    /** 申请授权：一定回调一次；被「拒绝且不再询问」时不发请求只报状态，六秒没回应算超时 */
    fun ask(cb: (Perm) -> Unit) {
        if (!Shizuku.pingBinder()) {
            cb(Perm.NO_SERVICE)
            return
        }
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        if (granted == PackageManager.PERMISSION_GRANTED) {
            cb(Perm.GRANTED)
            return
        }
        //官方语义：这里为 true 表示用户选过「拒绝且不再询问」，再 request 也不会弹窗，只能去 Shizuku 里手动开
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            cb(Perm.NEVER_ASK)
            return
        }
        ensureListener()
        val seq = ++askSeq
        permCb = { g ->
            if (seq == askSeq) {
                permCb = null
                cb(if (g) Perm.GRANTED else Perm.DENIED)
            }
        }
        main.postDelayed({
            if (seq == askSeq && permCb != null) {
                permCb = null
                cb(Perm.ERROR)
            }
        }, 6000)
        //requestPermission 只能在主线程发，且必须 binder 活着，否则抛 IllegalStateException
        main.post {
            runCatching { Shizuku.requestPermission(REQ_CODE) }.onFailure {
                if (seq == askSeq) {
                    permCb = null
                    cb(Perm.ERROR)
                }
            }
        }
    } //申请授权（带超时与永久拒绝判定）

    private fun ensureListener() {
        if (permListener != null) return
        val l = Shizuku.OnRequestPermissionResultListener { code, grant ->
            if (code == REQ_CODE) {
                val c = permCb
                permCb = null
                if (c != null) main.post { c.invoke(grant == PackageManager.PERMISSION_GRANTED) }
            }
        }
        permListener = l
        runCatching { Shizuku.addRequestPermissionResultListener(l) }
    } //监听只挂一次

    /** 拉起 Shizuku App：手动授权、启动服务都在这边做 */
    fun openManager(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG)
        if (i != null) {
            runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PKG"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    } //打开 Shizuku

    /** 经 IShizukuService.newProcess 以 shell/root 身份执行命令（与 Operit 同路） */
    fun run(cmd: String, input: String? = null, timeoutMs: Long = 120_000L): ShellResult {
        if (!Shizuku.pingBinder()) {
            return ShellResult(-1, "[Shizuku] 服务未运行：先在 Shizuku App 里启动服务")
        }
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            return ShellResult(-1, "[Shizuku] 未授权：先到权限步骤点「申请 Shizuku 授权」")
        }
        val svc = service() ?: return ShellResult(-1, "[Shizuku] 服务不可达：binder 在但拿不到 IShizukuService")
        var proc: Any? = null
        return try {
            proc = svc.newProcess(arrayOf("sh", "-c", cmd), null, null)
                ?: return ShellResult(-1, "[Shizuku] 创建远程进程失败")
            val pc = proc.javaClass
            val sb = StringBuilder()
            val readers = ArrayList<Thread>(2)
            readers += drain(pc.getMethod("getInputStream").invoke(proc) as ParcelFileDescriptor, sb)
            readers += drain(pc.getMethod("getErrorStream").invoke(proc) as ParcelFileDescriptor, sb)
            if (input != null) {
                runCatching {
                    (pc.getMethod("getOutputStream").invoke(proc) as OutputStream).use {
                        it.write(input.toByteArray())
                    }
                }
            }
            val mExit = pc.getMethod("exitValue")
            val deadline = System.currentTimeMillis() + timeoutMs
            var code = -1
            var done = false
            while (System.currentTimeMillis() < deadline) {
                done = try {
                    code = mExit.invoke(proc) as Int
                    true
                } catch (e: Exception) {
                    false
                }
                if (done) break
                Thread.sleep(120)
            }
            if (!done) {
                runCatching { pc.getMethod("destroy").invoke(proc) }
                readers.forEach { it.join(1500) }
                val text = synchronized(sb) { sb.toString() }.trim()
                return ShellResult(-1, "$text\n[超时 ${timeoutMs}ms，已强制结束]")
            }
            readers.forEach { it.join(2000) }
            ShellResult(code, synchronized(sb) { sb.toString() }.trim())
        } catch (e: Exception) {
            ShellResult(-1, "[Shizuku] 执行异常: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (proc != null) runCatching { proc.javaClass.getMethod("destroy").invoke(proc) }
        }
    } //直连执行命令

    private fun drain(fd: ParcelFileDescriptor, sb: StringBuilder): Thread = Thread {
        runCatching {
            FileInputStream(fd.fileDescriptor).bufferedReader().forEachLine { line ->
                synchronized(sb) { sb.appendLine(line) }
            }
        }
        runCatching { fd.close() }
    }.apply { isDaemon = true }
}