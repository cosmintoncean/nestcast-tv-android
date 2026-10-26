package com.castbox.tv

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Where people can support CastBox. Showing it sends nothing anywhere: the link is only text and a QR code. */
const val DONATE_URL = "https://ko-fi.com/comaapps"

/** Black-on-white QR code for [text], with a quiet zone so phone cameras read it from a TV. */
fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) AndroidColor.BLACK else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** "Support CastBox": the Ko-fi link as text plus a QR code to scan with a phone (a TV has no usable browser). */
@Composable
fun SupportDialog(onClose: () -> Unit) {
    val qr = remember { qrBitmap(DONATE_URL).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Support CastBox") },
        text = {
            Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CastBox is free and has no ads. If you enjoy it, scan this code with your phone to leave a tip.", fontSize = 15.sp)
                Image(qr, contentDescription = "QR code for $DONATE_URL", filterQuality = FilterQuality.None, modifier = Modifier.size(220.dp))
                Text(DONATE_URL, fontSize = 16.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
