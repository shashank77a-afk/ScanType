package com.scantype.documentscanner

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun Onboarding(onDone: () -> Unit) {
    val pages = listOf(
        Triple(Icons.Filled.DocumentScanner, "Scan documents", "Scan many pages into one document. Edges are cropped automatically, and you can add, retake, crop or reorder pages any time."),
        Triple(Icons.Filled.Description, "ID cards and handwriting", "Scan the front and back of an ID card onto one A4 page. Turn Hindi and English handwriting into editable text."),
        Triple(Icons.Filled.PictureAsPdf, "Create, export and share", "Make PDF and Word files, save images to your Gallery, print and share. Your files stay on your phone."))
    var i by remember { mutableIntStateOf(0) }
    val (icon, title, text) = pages[i]
    Column(Modifier.fillMaxSize().padding(28.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.scannera_logo), "Scanner.ai", Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
        Spacer(Modifier.height(24.dp))
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.indices.forEach { k ->
                Box(Modifier.size(10.dp).clip(CircleShape).background(
                    if (k == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onDone) { Text("Skip") }
            Button({ if (i < pages.size - 1) i++ else onDone() }) { Text(if (i < pages.size - 1) "Next" else "Get started") }
        }
    }
}
