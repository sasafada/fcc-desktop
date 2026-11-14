package app.fcc.claude

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.File
import kotlin.concurrent.thread

class FccServerService : Service() {

    companion object {
        const val ACTION_START = "app.fcc.claude.START_SERVER"
        const val ACTION_RESTART = "app.fcc.claude.RESTART_SERVER"
        private const val CHANNEL_ID = "fcc_server"
        private const val NOTIFICATION_ID = 8082
    }

    private val lock = Any()

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    private var generation = 0L

    @Volatile
    private var startingGeneration: Long? = null

    @Volatile
    private var taskRemoved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FCC Server")
            .setContentText("Mantendo o servidor local ativo na porta 8082")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        var oldProcess: Process? = null
        val currentGeneration: Long

        synchronized(lock) {
            taskRemoved = false

            if (intent?.action == ACTION_RESTART) {
                generation++
                oldProcess = serverProcess
                serverProcess = null
                startingGeneration = null
            } else {
                if (serverProcess?.isAlive == true ||
                    startingGeneration == generation
                ) {
                    return START_NOT_STICKY
                }
            }

            currentGeneration = generation
            startingGeneration = currentGeneration
        }

        stopProcess(oldProcess)

        thread(name = "fcc-server-launch") {
            try {
                launchServer(currentGeneration)
            } catch (e: Exception) {
                android.util.Log.e(
                    "FccServerService",
                    "Falha ao iniciar FCC Server",
                    e
                )

                synchronized(lock) {
                    if (generation == currentGeneration && !taskRemoved) {
                        stopSelf()
                    }
                }
            } finally {
                synchronized(lock) {
                    if (startingGeneration == currentGeneration) {
                        startingGeneration = null
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun launchServer(currentGeneration: Long) {
        check(isCurrent(currentGeneration)) {
            "Inicialização cancelada"
        }

        val root = Rootfs.dir(this)
        check(Rootfs.isReady(this)) {
            "Rootfs ainda não está preparado"
        }

        val nl = applicationInfo.nativeLibraryDir
        val tmp = File(filesDir, "tmp").apply { mkdirs() }

        File(root, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }

        val path =
            "/root/.local/bin:/root/.claude/local:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        val proot = "$nl/libproot.so"

        val args = listOf(
            proot,
            "--kill-on-exit", "--link2symlink", "-0",
            "-r", root.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${tmp.path}:/tmp", "-b", "${tmp.path}:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "TERM=xterm-256color", "COLORTERM=truecolor", "PATH=$path",
            "/bin/bash", "-lc", "exec fcc-server"
        )

        val process = ProcessBuilder(args)
            .directory(root)
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_TMP_DIR"] = tmp.path
                environment()["PROOT_LOADER"] = "$nl/libproot-loader.so"
                environment()["LD_LIBRARY_PATH"] = nl
                environment()["PROOT_NO_SECCOMP"] = "1"
                environment()["TMPDIR"] = tmp.path
            }
            .start()

        val accepted = synchronized(lock) {
            if (generation == currentGeneration && !taskRemoved) {
                serverProcess = process
                true
            } else {
                false
            }
        }

        if (!accepted) {
            stopProcess(process)
            return
        }

        thread(name = "fcc-server-log") {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        android.util.Log.i("FCC-Server", it)
                    }
                }
            } catch (_: Exception) {
            }
        }

        val exitCode = process.waitFor()
        android.util.Log.i(
            "FccServerService",
            "FCC Server terminou: $exitCode"
        )

        synchronized(lock) {
            if (serverProcess === process) {
                serverProcess = null

                if (generation == currentGeneration && !taskRemoved) {
                    stopSelf()
                }
            }
        }
    }

    private fun isCurrent(expected: Long): Boolean {
        return synchronized(lock) {
            generation == expected && !taskRemoved
        }
    }

    private fun stopProcess(process: Process?) {
        if (process == null) return

        try {
            process.destroy()

            if (!process.waitFor(
                    1500,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            ) {
                process.destroyForcibly()
                process.waitFor(
                    1500,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            android.util.Log.w(
                "FccServerService",
                "Erro ao encerrar processo",
                e
            )
        }
    }

    private fun stopServerProcess() {
        val process = synchronized(lock) {
            generation++
            startingGeneration = null

            val old = serverProcess
            serverProcess = null
            old
        }

        stopProcess(process)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FCC Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Servidor local necessário para o FCC Claude"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        synchronized(lock) {
            taskRemoved = true
        }

        stopServerProcess()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        synchronized(lock) {
            taskRemoved = true
        }

        stopServerProcess()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
