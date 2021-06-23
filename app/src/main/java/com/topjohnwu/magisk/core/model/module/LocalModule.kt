package com.topjohnwu.magisk.core.model.module

import com.topjohnwu.magisk.core.Const
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class LocalModule(path: String) : Module() {
    override var id: String = ""
    override var name: String = ""
    override var author: String = ""
    override var version: String = ""
    override var versionCode: Int = -1
    override var description: String = ""

    private val removeFile = SuFile(path, "remove")
    private val disableFile = SuFile(path, "disable")
    private val updateFile = SuFile(path, "update")
    private val ruleFile = SuFile(path, "sepolicy.rule")

    val updated: Boolean get() = updateFile.exists()

    var enable: Boolean
        get() = !disableFile.exists()
        set(enable) {
            val dir = "$PERSIST/$id"
            if (enable) {
                disableFile.delete()
                if (Const.Version.atLeast_21_2())
                    Shell.su("copy_sepolicy_rules").submit()
                else
                    Shell.su("mkdir -p $dir", "cp -af $ruleFile $dir").submit()
            } else {
                !disableFile.createNewFile()
                if (Const.Version.atLeast_21_2())
                    Shell.su("copy_sepolicy_rules").submit()
                else
                    Shell.su("rm -rf $dir").submit()
            }
        }

    var remove: Boolean
        get() = removeFile.exists()
        set(remove) {
            if (remove) {
                if (updateFile.exists()) return
                removeFile.createNewFile()
                if (Const.Version.atLeast_21_2())
                    Shell.su("copy_sepolicy_rules").submit()
                else
                    Shell.su("rm -rf $PERSIST/$id").submit()
            } else {
                removeFile.delete()
                if (Const.Version.atLeast_21_2())
                    Shell.su("copy_sepolicy_rules").submit()
                else
                    Shell.su("cp -af $ruleFile $PERSIST/$id").submit()
            }
        }

    init {
        runCatching {
            parseProps(Shell.su("dos2unix < $path/module.prop").exec().out)
        }

        if (id.isEmpty()) {
            val sep = path.lastIndexOf('/')
            id = path.substring(sep + 1)
        }

        if (name.isEmpty()) {
            name = id
        }
    }

    companion object {

        private val PERSIST get() = "${Const.MAGISKTMP}/mirror/persist/magisk"

        suspend fun installed() = withContext(Dispatchers.IO) {
            SuFile(Const.MAGISK_PATH)
                .listFiles { _, name -> name != "lost+found" && name != ".core" }
                .orEmpty()
                .filter { !it.isFile }
                .map { LocalModule("${Const.MAGISK_PATH}/${it.name}") }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }
}
