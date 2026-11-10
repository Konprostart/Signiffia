package dev.signiffia.ui

import java.util.Locale

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatDate(millis: Long): String {
    if (millis <= 0) return "—"
    val formatter = java.text.SimpleDateFormat("dd.MM.yyyy. HH:mm", Locale.getDefault())
    return formatter.format(java.util.Date(millis))
}
