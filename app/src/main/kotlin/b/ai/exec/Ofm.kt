package b.ai.exec

import java.io.File

/** Our Free Model 独立服务控制器：一键启动/停止/探活/日志。
 *  与酒馆同一套模式：App 用 spawn() 拉起长驻进程，挂在应用进程下，走应用自己的网络。
 *  网关地址 http://127.0.0.1:18900/v1，可在模型配置里当自定义 base_url 用 */
object Ofm {

    private var proc: java.lang.Process? = null

    private fun svcDir() = File(Linux.rootfs, "opt/ofm/packages/standalone")
    private fun cli() = File(svcDir(), "cli.mjs")
    private fun node22() = File(Linux.rootfs, "opt/node22/bin/node")
    private fun logFile() = File(Linux.rootfs, "root/ofm.log")

    /** 就绪判定：Node22 + 服务本体都在 */
    fun installed(): Boolean = Linux.installed() && cli().isFile && node22().isFile

    /** 端口探活：guest 内发一个 HTTP 请求 */
    fun alive(): Boolean {
        if (!installed()) return false
        val r = Linux.run(
            "/opt/node22/bin/node -e \"require('http').get('http://127.0.0.1:18900/',r=>{console.log(r.statusCode);process.exit(0)}).on('error',()=>process.exit(1))\"",
            20_000L
        )
        return r.ok && r.out.trim().startsWith("200")
    }

    /** 启动：拉起后轮询端口，最多等 40 秒 */
    @Synchronized
    fun start(onLog: (String) -> Unit): Boolean {
        if (!installed()) {
            onLog("服务本体不在（/opt/ofm 缺失）：先装 Ubuntu 环境")
            return false
        }
        if (alive()) {
            onLog("服务已在运行：http://127.0.0.1:18900/")
            return true
        }
        onLog("启动中…（拉取上游模型清单约 10-30 秒）")
        val p = Linux.spawn(
            "export OUR_FREE_MODEL_FREE_TRANSPORT=node; " +
                "cd /opt/ofm/packages/standalone && exec /opt/node22/bin/node cli.mjs --port 18900 >> /root/ofm.log 2>&1"
        )
        if (p == null) {
            onLog("拉起失败：proot 或 rootfs 不可用")
            return false
        }
        proc = p
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 40_000) {
            if (!p.isAlive) {
                onLog("进程退出了：点「日志」查原因")
                proc = null
                return false
            }
            if (alive()) {
                onLog("服务已就绪：网关 http://127.0.0.1:18900/v1（模型设置里 base_url 填这个）")
                return true
            }
            Thread.sleep(2000)
        }
        onLog("40 秒内没监听上：点「日志」看 ofm.log 排查")
        return false
    }

    /** 停止：guest 内按命令行特征杀进程 */
    @Synchronized
    fun stop(onLog: (String) -> Unit): Boolean {
        val r = Linux.run(
            "pkill -f 'node cli.mjs' >/dev/null 2>&1; sleep 1; pgrep -f 'node cli.mjs' >/dev/null && echo STILL || echo GONE",
            30_000L
        )
        proc = null
        val gone = r.ok && r.out.contains("GONE")
        onLog(if (gone) "服务已停止" else "停止命令已发出（若有残留，重启手机后即清）")
        return gone
    }

    /** 日志尾部 */
    fun tail(): String = runCatching { logFile().readText().takeLast(2400) }.getOrDefault("（暂无日志）")
} //ofm 独立服务控制器
