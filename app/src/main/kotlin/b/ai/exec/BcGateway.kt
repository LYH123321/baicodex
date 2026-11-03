package b.ai.exec

import java.io.File

/** bc-gateway 智能调度网关：一键启动/停止/探活/日志。
 *  多上游聚合（ofm 免费池 + UnoRouter + AI Horde），动态池轮换 + 分级熔断 + 自动换批。
 *  网关地址 http://127.0.0.1:18750/v1，模型名填 auto */
object BcGateway {

    private var proc: java.lang.Process? = null

    private fun dir() = File(Linux.rootfs, "root/bc-gateway")
    private fun gw() = File(dir(), "gateway.py")
    private fun runAll() = File(dir(), "run-all.sh")
    private fun logFile() = File(dir(), "gw.log")

    /** 就绪判定：网关脚本 + 看门狗都在 */
    fun installed(): Boolean = Linux.installed() && gw().isFile && runAll().isFile

    /** 端口探活：guest 内发一个 HTTP 请求 */
    fun alive(): Boolean {
        if (!installed()) return false
        val r = Linux.run(
            "python3 -c \"import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:18750/',timeout=5).status)\"",
            20_000L
        )
        return r.ok && r.out.trim().startsWith("200")
    }

    /** 启动：nohup 拉起 run-all.sh（两个守护循环），挂 App 进程下；轮询端口最多 20 秒 */
    @Synchronized
    fun start(onLog: (String) -> Unit): Boolean {
        if (!installed()) {
            onLog("服务本体不在（/root/bc-gateway 缺失）：先装 Ubuntu 环境")
            return false
        }
        if (alive()) {
            onLog("服务已在运行：http://127.0.0.1:18750/v1")
            return true
        }
        onLog("启动中…（看门狗拉起网关+Horde 桥）")
        val p = Linux.spawn("nohup /bin/sh /root/bc-gateway/run-all.sh >> /root/bc-gateway/gw.log 2>&1")
        if (p == null) {
            onLog("拉起失败：proot 或 rootfs 不可用")
            return false
        }
        proc = p
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 20_000) {
            if (alive()) {
                onLog("服务已就绪：网关 http://127.0.0.1:18750/v1（模型名填 auto，密钥随意）")
                return true
            }
            Thread.sleep(1500)
        }
        onLog("20 秒内没监听上：点「日志」看 gw.log 排查")
        return false
    }

    /** 停止：guest 内按脚本特征杀进程 */
    @Synchronized
    fun stop(onLog: (String) -> Unit): Boolean {
        val r = Linux.run(
            "pkill -f 'run-all.sh' >/dev/null 2>&1; pkill -f 'gateway.py' >/dev/null 2>&1; pkill -f 'bridge.py' >/dev/null 2>&1; sleep 1; echo DONE",
            30_000L
        )
        proc = null
        val ok = r.ok
        onLog(if (ok) "服务已停止" else "停止命令已发出（若有残留，重启手机后即清）")
        return ok
    }

    /** 日志尾部 */
    fun tail(): String = runCatching { logFile().readText().takeLast(2400) }.getOrDefault("（暂无日志）")
} //bc-gateway 智能调度网关控制器
