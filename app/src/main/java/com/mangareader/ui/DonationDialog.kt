package com.mangareader.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mangareader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DonationDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                val msg = withContext(Dispatchers.IO) { saveDonationQr(ctx) }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(ctx, "保存需要存储权限", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("请作者喝杯咖啡", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "漫读始终免费、无广告、没有任何商业收益。如果它陪你度过了一段愉快的阅读时光，欢迎扫码请瑾轩小狼喝杯咖啡。",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Image(
                    painter = painterResource(R.drawable.donation_qr),
                    contentDescription = "捐赠收款码",
                    modifier = Modifier
                        .size(240.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (Build.VERSION.SDK_INT < 29 &&
                                    ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    scope.launch {
                                        val msg = withContext(Dispatchers.IO) { saveDonationQr(ctx) }
                                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "（长按图片保存到相册）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "随心就好，金额随意。没有这份心意也完全没关系，用得开心就是最好的支持。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "有问题或建议，也可以直接加 QQ 反馈：1173350134（点击复制）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable {
                        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ", "1173350134"))
                        Toast.makeText(ctx, "QQ 号已复制", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(Modifier.height(16.dp))
                com.mangareader.updatecheck.UpdateCheckPanel(onDismiss = onDismiss)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

private fun saveDonationQr(ctx: Context): String {
    return try {
        val bmp = BitmapFactory.decodeResource(ctx.resources, R.drawable.donation_qr)
            ?: return "保存失败"
        val name = "mandu_donation_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/漫读")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return saveDonationQrFallback(ctx, bmp)
            val ok = resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            if (!ok) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                return saveDonationQrFallback(ctx, bmp)
            }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            "已保存到相册 Pictures/漫读"
        } else {
            val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "漫读")
            dir.mkdirs()
            val f = java.io.File(dir, name)
            java.io.FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/png"), null)
            "已保存到相册 Pictures/漫读"
        }
    } catch (e: Exception) {
        "保存失败"
    }
}

private fun saveDonationQrFallback(ctx: Context, bmp: Bitmap): String {
    return try {
        val dir = java.io.File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "漫读")
        dir.mkdirs()
        val f = java.io.File(dir, "mandu_donation_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        "已保存到应用私有目录 ${f.absolutePath}"
    } catch (e: Exception) {
        "保存失败"
    }
}