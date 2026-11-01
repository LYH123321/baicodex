package b.ai.exec

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 环境配置（移植自 Operit 的环境安装页）：按组件勾选，在 proot Ubuntu 里装开发环境。
 *  组件状态直接看 rootfs 里的二进制，不另记状态文件。apt 不能真并发（dpkg 锁），
 *  所以选中的组件合成一条 apt 命令一次装完，探测与日志照常并行。 */
object DevEnv {
    data class Comp(
        val id: String,
        val name: String,
        val note: String,
        val pkgs: List<String>,
        val probe: (File) -> Boolean
    )

    data class Status(
        val comp: Comp,
        val installed: Boolean,
        val selected: Boolean = false
    )

    @Volatile var lastLog: String = ""
        private set
    private val busy = AtomicBoolean(false)

    val running: Boolean get() = busy.get()

    private fun rootfs(): File = Linux.rootfs

    /** 组件清单：探针只认 rootfs 里的实际文件 */
    fun comps(): List<Comp> = listOf(
        Comp("node", "Node.js", "Node.js 和前端开发环境", listOf("nodejs", "npm")) {
            File(it, "usr/bin/node").isFile
        },
        Comp("python", "Python", "Python 开发环境", listOf("python3", "python3-pip", "python3-venv")) {
            File(it, "usr/bin/python3").isFile
        },
        Comp("ssh", "SSH 工具", "SSH 客户端和密钥认证工具", listOf("openssh-client")) {
            File(it, "usr/bin/ssh").isFile
        },
        Comp("java", "Java", "Java 开发环境（JDK 17）", listOf("openjdk-17-jdk-headless")) {
            File(it, "usr/lib/jvm").isDirectory
        },
        Comp("rust", "Rust (Cargo)", "Rust 开发环境和包管理器", listOf("rustc", "cargo")) {
            File(it, "usr/bin/cargo").isFile
        },
        Comp("go", "Go", "Go 语言开发环境", listOf("golang-go")) {
            File(it, "usr/bin/go").isFile
        }
    )

    /** 各组件当前状态（UI 列表用） */
    fun status(): List<Status> {
        val rf = rootfs()
        return comps().map { Status(it, it.probe(rf)) }
    }

    /** 并行安装入口：选中的组件一次 apt 装完；没装 Ubuntu 直接 false */
    fun install(ids: List<String>, onLog: (String) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) {
            onLog("已有安装任务在跑，等它结束再点")
            return false
        }
        try {
            if (!Linux.installed()) {
                onLog("先装 Ubuntu 环境，再配开发环境")
                return false
            }
            val all = comps().filter { it.id in ids }
            if (all.isEmpty()) {
                onLog("没勾选任何组件")
                return false
            }
            //先做一次通道诊断：proot 能不能在应用身份下起、能不能读到 rootfs
            val diag = Linux.run("id; echo DIAG_OK; uname -m", 60_000L)
            onLog("通道诊断：exit=${diag.code} out=${diag.out.take(150).ifBlank { "<空>" }}")
            //先保证 DNS 可用：rootfs 可能没写 resolv.conf，apt 会全部解析失败
            val dns = Linux.run(
                "mkdir -p /etc; printf 'nameserver 8.8.8.8\\nnameserver 1.1.1.1\\n' > /etc/resolv.conf; cat /etc/resolv.conf",
                30_000L
            )
            onLog("DNS 配置：${dns.out.takeLast(120)}")
            val pkgs = all.flatMap { it.pkgs }.distinct()
            onLog("开始配置：${all.joinToString("、") { it.name }}（${pkgs.size} 个包，并行探测 + 串行安装）")
            val u = Linux.run("export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a >/dev/null 2>&1; apt-get -o Acquire::http::Timeout=30 -o Acquire::Retries=3 -o Acquire::ForceIPv4=true update -y", 900_000L)
            if (!u.ok) {
                onLog("apt update 退出码 ${u.code}，输出末尾：")
                onLog(u.out.takeLast(600))
                onLog("继续尝试安装")
            }
            onLog("apt-get install -y ${pkgs.joinToString(" ")}")
            val r = Linux.run(
"export DEBIAN_FRONTEND=noninteractive; " +
"dpkg --configure -a >/dev/null 2>&1; " +
"apt-get -o Acquire::http::Timeout=30 -o Acquire::Retries=3 -o Acquire::ForceIPv4=true install -y --no-install-recommends ${pkgs.joinToString(" ")}",
                1_800_000L
            )
            lastLog = r.out.takeLast(1200)
            onLog(lastLog)
            if (!r.ok) {
                onLog("apt install 退出码 ${r.code}，输出末尾：")
                onLog(r.out.takeLast(800))
            }
            val rf = rootfs()
            val report = all.map { "${it.name}=${if (it.probe(rf)) "OK" else "未装上"}" }
            val ok = all.all { it.probe(rf) }
            onLog(if (ok) "全部就绪：${report.joinToString(" ")}" else "部分未就绪：${report.joinToString(" ")}，可再点一次补装")
            return ok
        } finally {
            busy.set(false)
        }
    } //一次装完勾选的组件
}