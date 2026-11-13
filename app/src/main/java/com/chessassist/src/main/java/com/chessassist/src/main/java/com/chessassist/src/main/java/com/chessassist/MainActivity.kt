package com.chessassist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqCapture = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding((20 * dp).toInt(), (64 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())

        val tv = TextView(this)
        tv.textSize = 16f
        tv.text = "ChessAssist\n\n" +
            "1. Toque em Ativar e aceite as permissões (sobreposição e captura de tela; escolha tela inteira).\n" +
            "2. Abra o Chess.com e comece uma partida. Deixe o tabuleiro na posição inicial.\n" +
            "3. No painel flutuante: toque Cantos, depois toque no canto superior esquerdo e no inferior direito do tabuleiro.\n" +
            "4. Toque Brancas ou Pretas (o seu lado) para o app aprender as peças.\n" +
            "5. Jogue: as casas de origem (verde) e destino (vermelho) aparecem contornadas.\n\n" +
            "Arraste o painel para fora do tabuleiro (segure no texto dele). Só funciona com o celular na vertical."
        root.addView(tv)

        val btn = Button(this)
        btn.text = "Ativar"
        btn.setOnClickListener { activate() }
        root.addView(btn)

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun activate() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Ative 'Exibir sobre outros apps', volte e toque Ativar de novo.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, reqCapture)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
