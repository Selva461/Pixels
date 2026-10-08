package androidx.core.content

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

open class FileProvider : android.content.ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        @JvmStatic fun getUriForFile(context: Context, authority: String, file: File): Uri = TODO()
    }
}

object IntentCompat {
    @JvmStatic fun <T> getParcelableExtra(intent: Intent, name: String?, clazz: Class<T>): T? = TODO()
}
