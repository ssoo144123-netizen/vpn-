package com.example.appfirewall

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException

class FirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        stopVpn()

        val blocked = PrefsManager(this).getBlockedApps()
        if (blocked.isEmpty()) {
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession("App Firewall")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)

        // فقط اپ‌های مسدودشده را داخل تونل می‌بریم
        var added = 0
        for (pkg in blocked) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (e: PackageManager.NameNotFoundException) {
                // اپ دیگر نصب نیست؛ نادیده گرفته می‌شود
            }
        }

        if (added == 0) {
            stopSelf()
            return
        }

        val pfd = builder.establish()
        if (pfd == null) {
            // اجازهٔ VPN لغو شده است
            stopSelf()
            return
        }
        vpnInterface = pfd

        // بسته‌های اپ‌های مسدودشده را می‌خوانیم و دور می‌ریزیم
        readerThread = Thread { drainPackets(pfd) }.apply {
            isDaemon = true
            start()
        }
    }

    private fun drainPackets(pfd: ParcelFileDescriptor) {
        try {
            val input = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)
            while (!Thread.currentThread().isInterrupted) {
                val length = input.read(buffer)
                if (length < 0) break
                // عمداً چیزی ارسال نمی‌شود، بنابراین اینترنت اپ مسدود می‌ماند
            }
        } catch (e: IOException) {
            // وقتی تونل بسته شود، این خطا طبیعی است
        }
    }

    private fun stopVpn() {
        readerThread?.interrupt()
        readerThread = null
        vpnInterface?.close()
        vpnInterface = null
    }

    override fun onRevoke() {
        // کاربر اجازهٔ VPN را از تنظیمات سیستم لغو کرده است
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.appfirewall.STOP"

        fun start(context: Context) {
            context.startService(Intent(context, FirewallVpnService::class.java))
        }

        fun stop(context: Context) {
            val intent = Intent(context, FirewallVpnService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
