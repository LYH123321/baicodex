package b.ai.exec

import java.io.File

/** SillyTavern 酒馆控制器：一键启动/停止/探活/日志。
 *  服务进程由 App 用 spawn() 拉起，挂在应用进程下；ST 日志落在 guest 的 /home/st/st.log */
object Tavern {

    private var proc: java.lang.Process? = null

    private fun stDir() = File(Linux.rootfs, "home/st")
    private fun logFile() = File(stDir(), "st.log")

    /** 就绪判定：Ubuntu 装好 + 酒馆本体在位（server.js） */
    fun installed(): Boolean = Linux.installed() && File(stDir(), "server.js").isFile

    /** 端口探活：guest 内发一个 HTTP 请求，比看进程列表可靠 */
    fun alive(): Boolean {
        if (!installed()) return false
        val r = Linux.run(
            "node -e \"require('http').get('http://127.0.0.1:8000/',r=>{console.log(r.statusCode);process.exit(0)}).on('error',()=>process.exit(1))\"",
            20_000L
        )
        return r.ok && r.out.trim().startsWith("200")
    }

    /** 启动（已在跑就直接报就绪）：拉起后轮询端口，最多等 60 秒 */
    @Synchronized
    fun start(onLog: (String) -> Unit): Boolean {
        if (!installed()) {
            onLog("酒馆本体不在（/home/st/server.js 缺失）：先装 Ubuntu 环境")
            return false
        }
        if (alive()) {
            onLog("酒馆已在运行：http://127.0.0.1:8000/")
            return true
        }
        onLog("启动中…（首次编译前端约 20-40 秒）")
        val p = Linux.spawn(
            "cd /home/st && exec node server.js --listen --port 8000 --disableCsrf >> /home/st/st.log 2>&1"
        )
        if (p == null) {
            onLog("拉起失败：proot 或 rootfs 不可用")
            return false
        }
        proc = p
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 60_000) {
            if (!p.isAlive) {
                onLog("进程退出了：看日志查原因")
                proc = null
                return false
            }
            if (alive()) {
                onLog("酒馆已就绪：http://127.0.0.1:8000/")
                return true
            }
            Thread.sleep(2500)
        }
        onLog("60 秒内没监听上：点「日志」看 st.log 排查")
        return false
    }

    /** 停止：guest 内 pkill node server.js */
    @Synchronized
    fun stop(onLog: (String) -> Unit): Boolean {
        val r = Linux.run(
            "pkill -f 'node server.js' >/dev/null 2>&1; sleep 1; pgrep -f 'node server.js' >/dev/null && echo STILL || echo GONE",
            30_000L
        )
        proc = null
        val gone = r.ok && r.out.contains("GONE")
        onLog(if (gone) "酒馆已停止" else "停止命令已发出（若有残留，重启手机后即清）")
        return gone
    }

    /** 日志尾部：给 UI 的「日志」按钮看 */
    fun tail(): String = runCatching { logFile().readText().takeLast(2400) }.getOrDefault("（暂无日志）")
} //酒馆控制器