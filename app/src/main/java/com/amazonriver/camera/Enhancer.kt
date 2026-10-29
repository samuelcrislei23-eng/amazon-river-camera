package com.amazonriver.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.OutputStream

object Enhancer {
    fun process(src: File, out: OutputStream) {
        var bmp = BitmapFactory.decodeFile(src.path) ?: throw IllegalStateException("decode")
        val o = ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        val rot = when (o) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        val flip = o == 2
        if (rot != 0f || flip) {
            val m = Matrix()
            if (flip) m.postScale(-1f, 1f)
            if (rot != 0f) m.postRotate(rot)
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle(); bmp = r
        }
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        val hist = IntArray(256)
        for (p in px) hist[(((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8]++
        val cut = px.size * 0.005
        var acc = 0; var lo = 0
        while (lo < 255 && acc + hist[lo] < cut) { acc += hist[lo]; lo++ }
        acc = 0; var hi = 255
        while (hi > 0 && acc + hist[hi] < cut) { acc += hist[hi]; hi-- }
        lo = minOf(lo, 40); hi = maxOf(hi, 215)
        val lut = IntArray(256) { ((it - lo) * 255f / (hi - lo)).coerceIn(0f, 255f).toInt() }

        for (i in px.indices) {
            val p = px[i]
            var r = lut[(p shr 16) and 255]; var g = lut[(p shr 8) and 255]; var b = lut[p and 255]
            val y = (r * 77 + g * 150 + b * 29) shr 8
            r = (y + (((r - y) * 9) shr 3)).coerceIn(0, 255)
            g = (y + (((g - y) * 9) shr 3)).coerceIn(0, 255)
            b = (y + (((b - y) * 9) shr 3)).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val res = px.copyOf()
        for (y in 1 until h - 1) {
            var idx = y * w + 1
            for (x in 1 until w - 1) {
                val c = px[idx]; val l = px[idx - 1]; val r = px[idx + 1]; val u = px[idx - w]; val d = px[idx + w]
                res[idx] = (0xFF shl 24) or (ch(c, l, r, u, d, 16) shl 16) or
                    (ch(c, l, r, u, d, 8) shl 8) or ch(c, l, r, u, d, 0)
                idx++
            }
        }
        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(res, 0, w, 0, 0, w, h)
        outBmp.compress(Bitmap.CompressFormat.JPEG, 98, out)
        outBmp.recycle()
    }

    private fun ch(c: Int, l: Int, r: Int, u: Int, d: Int, s: Int): Int {
        val cc = (c shr s) and 255
        val diff = 4 * cc - ((l shr s) and 255) - ((r shr s) and 255) - ((u shr s) and 255) - ((d shr s) and 255)
        return (cc + ((diff * 3) shr 3)).coerceIn(0, 255)
    }
}
