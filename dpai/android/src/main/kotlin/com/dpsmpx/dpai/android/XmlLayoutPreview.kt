package com.dpsmpx.dpai.android

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Предпросмотр разметки XML на телефоне (`DPmetaApp:D-132`; D-168): разметка берётся
 * из ресурсов установленного APK, его код в процесс DPmetaApp не загружается
 * (`createPackageContext` без `CONTEXT_INCLUDE_CODE`). Виды Android — настоящие,
 * прочие (AndroidX, Material, свои) — [Placeholder] с именем класса; тема — тема
 * приложения из его манифеста. Звать из главного потока.
 */
internal object XmlLayoutPreview {
    /** Имя разметки по пути файла `…/res/layout/name.xml` (и `layout-…`); иначе `null`. */
    fun layoutName(path: String): String? {
        val parts = path.split('/')
        if (parts.size < 3) return null
        val dir = parts[parts.size - 2]
        val res = parts[parts.size - 3]
        val name = parts.last()
        if (res != "res" || !(dir == "layout" || dir.startsWith("layout-")) || !name.endsWith(".xml")) return null
        return name.removeSuffix(".xml").takeIf { it.matches(Regex("[a-z0-9_]+")) }
    }

    /** Нарисовать разметку [layout] пакета [pkg] шириной [widthDp]; высота — по содержимому, не больше [MAX_HEIGHT_DP]. */
    fun render(context: Context, pkg: String, layout: String, widthDp: Int = 360): Outcome<Bitmap> {
        val foreign = try {
            context.createPackageContext(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "пакет $pkg не виден DP_IDE: Android показывает приложению только пакеты, которые оно установило — поставьте APK через «Артефакты»")
        }
        val res = foreign.resources
        // Разметка чужого APK — только по имени: класса R того приложения у DPmetaApp нет (D-168).
        @android.annotation.SuppressLint("DiscouragedApi")
        val id = res.getIdentifier(layout, "layout", pkg)
        if (id == 0) return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "в установленном APK нет разметки $layout — соберите и установите его заново")
        val theme = runCatching { context.packageManager.getApplicationInfo(pkg, 0).theme }.getOrDefault(0)
        val themed = ContextThemeWrapper(foreign, if (theme != 0) theme else android.R.style.Theme_DeviceDefault_Light)
        val inflater = LayoutInflater.from(themed).cloneInContext(themed)
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, ctx: Context, attrs: AttributeSet): View? =
                if (foreignClass(name)) Placeholder(ctx, attrs, name) else null

            override fun onCreateView(name: String, ctx: Context, attrs: AttributeSet): View? = onCreateView(null, name, ctx, attrs)
        }
        val root = FrameLayout(themed)
        val view = try {
            inflater.inflate(id, root, false)
        } catch (e: RuntimeException) {
            // Разметка ссылается на то, чего нет без кода приложения: сказать словами.
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "разметка не разобралась без кода приложения: ${e.javaClass.simpleName} ${e.message?.take(160).orEmpty()}")
        }
        root.addView(view)
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val maxHeight = (MAX_HEIGHT_DP * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val height = root.measuredHeight.coerceIn(1, maxHeight)
        root.layout(0, 0, width, height)
        val bmp = createBitmap(width, height)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        root.draw(canvas)
        return Outcome.success(bmp)
    }

    /** Класс не из Android: его код не загружается — заглушка. */
    private fun foreignClass(name: String): Boolean = name == "fragment" || ('.' in name && !name.startsWith("android."))

    /** Заглушка вида без кода: рамка и короткое имя класса; дети раскладываются как в FrameLayout. */
    private class Placeholder(context: Context, attrs: AttributeSet, name: String) : FrameLayout(context, attrs) {
        private val label = name.substringAfterLast('.')
        private val density = context.resources.displayMetrics.density
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(0x60, 0x7D, 0x8B)
            strokeWidth = density
            pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x45, 0x5A, 0x64); textSize = 11 * density }

        init {
            setWillNotDraw(false)
            minimumHeight = (24 * density).toInt()
            minimumWidth = (48 * density).toInt()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(density, density, width - density, height - density, border)
            canvas.drawText(label, 4 * density, 14 * density, text)
        }
    }

    const val MAX_HEIGHT_DP: Int = 2000
}
