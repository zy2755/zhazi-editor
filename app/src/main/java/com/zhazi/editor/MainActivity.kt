package com.zhazi.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.zhazi.editor.docx.DocxDocument
import com.zhazi.editor.docx.DocxEditor
import com.zhazi.editor.docx.DocxParser
import com.zhazi.editor.docx.DocxZip
import com.zhazi.editor.docx.FontPatch
import com.zhazi.editor.docx.RprOps
import com.zhazi.editor.docx.RunFmt
import com.zhazi.editor.docx.Segmenter
import com.zhazi.editor.ui.FormatBar
import com.zhazi.editor.ui.ParaChips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    val state = EditorState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ZhaZiApp(state) }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            loadFromUri(intent.data!!)
        } else if (BuildConfig.DEBUG && intent.getStringExtra("debug_path") != null) {
            // 仅 debug 构建：跳过文件选择器直接加载 filesDir 下的文档（供自动化冒烟测试）
            loadFromFile(File(filesDir, intent.getStringExtra("debug_path")!!))
        }
    }

    fun loadFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选文件")
                }
                applyLoaded(bytes, queryDisplayName(this@MainActivity, uri)
                    ?: uri.lastPathSegment ?: "docx")
            } catch (t: Throwable) {
                state.errorMsg = "打开失败：${t.message}"
            }
        }
    }

    fun loadFromFile(file: File) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    if (!file.exists()) error("文件不存在：${file.name}")
                    file.readBytes()
                }
                applyLoaded(bytes, file.name)
            } catch (t: Throwable) {
                state.errorMsg = "打开失败：${t.message}"
            }
        }
    }

    private fun applyLoaded(bytes: ByteArray, name: String) {
        try {
            val x = DocxZip.readDocumentXml(bytes)
                ?: error("不是有效的 .docx（未找到 word/document.xml）")
            state.original = bytes
            state.xml = x
            state.fileName = name
            state.undoStack.clear()
            state.selPara = -1
            state.selChips = emptySet()
            state.chipOverrides.clear()
        } catch (t: Throwable) {
            state.errorMsg = "打开失败：${t.message}"
        }
    }

    fun saveTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                val original = state.original ?: error("尚未打开文档")
                val cur = state.xml ?: error("没有内容可保存")
                val data = withContext(Dispatchers.IO) { DocxZip.rewrite(original, cur) }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                        ?: error("无法写入所选位置")
                }
                state.errorMsg = "已保存 ✓"
            } catch (t: Throwable) {
                state.errorMsg = "保存失败：${t.message}"
            }
        }
    }
}

/** 全部编辑器状态 */
class EditorState {
    var original: ByteArray? = null
    val undoStack = mutableStateListOf<String>()
    val chipOverrides = mutableStateMapOf<Int, List<IntRange>>()
    var fileName by mutableStateOf("")
    var xml by mutableStateOf<String?>(null)
    var selPara by mutableStateOf(-1)
    var selChips by mutableStateOf<Set<Int>>(emptySet())
    var errorMsg by mutableStateOf<String?>(null)
    var brushActive by mutableStateOf(false)
    var brushPatch by mutableStateOf<FontPatch?>(null)

    /** 关闭文档回到首页 */
    fun close() {
        xml = null
        original = null
        fileName = ""
        undoStack.clear()
        selPara = -1
        selChips = emptySet()
        chipOverrides.clear()
        brushActive = false
        brushPatch = null
    }

    /** 以当前选中的词块格式为源，激活格式刷 */
    fun startBrush(doc: DocxDocument) {
        val fmt = firstSelFmt(doc) ?: return
        brushPatch = FontPatch(
            fontSizeHp = fmt.sizeHp,
            sizeReset = fmt.sizeHp == null,
            colorHex = fmt.colorHex,
            colorReset = fmt.colorHex == null,
            font = fmt.font,
            fontReset = fmt.font == null,
            bold = fmt.bold,
            boldReset = fmt.bold == null,
            italic = fmt.italic,
            italicReset = fmt.italic == null,
        )
        brushActive = true
    }

    fun stopBrush() {
        brushActive = false
        brushPatch = null
    }

    /** 当前段落的词块表：用户定制优先，否则默认分词 */
    fun chipsOf(doc: DocxDocument, paraIdx: Int): List<IntRange> {
        chipOverrides[paraIdx]?.let { return it }
        val para = doc.paragraphs.getOrNull(paraIdx) ?: return emptyList()
        return Segmenter.chips(para.text)
    }

    /** 刷子模式下：把源格式应用到单个词块（不退出刷子模式，可连续刷） */
    fun applyBrushToChip(doc: DocxDocument, paraIdx: Int, chipIdx: Int) {
        val patch = brushPatch ?: return
        val chip = chipsOf(doc, paraIdx).getOrNull(chipIdx) ?: return
        val cur = xml ?: return
        undoStack.add(cur)
        if (undoStack.size > 50) undoStack.removeAt(0)
        xml = DocxEditor.applyFormat(doc, paraIdx, chip.first, chip.last + 1, patch)
    }

    fun toggleChip(paraIdx: Int, chipIdx: Int) {
        if (selPara != paraIdx) {
            selPara = paraIdx
            selChips = setOf(chipIdx)
        } else {
            selChips = if (chipIdx in selChips) selChips - chipIdx else selChips + chipIdx
        }
    }

    /** 双击词块：拆成单字块 */
    fun splitChip(doc: DocxDocument, paraIdx: Int, chipIdx: Int) {
        val chips = chipsOf(doc, paraIdx)
        val target = chips.getOrNull(chipIdx) ?: return
        if (target.first == target.last) return // 已是单字
        val out = ArrayList<IntRange>(chips.size)
        chips.forEachIndexed { i, r ->
            if (i == chipIdx) {
                var p = r.first
                while (p <= r.last) {
                    out.add(p..p)
                    p++
                }
            } else {
                out.add(r)
            }
        }
        chipOverrides[paraIdx] = out
        selChips = emptySet() // 结构变化后选择索引会漂移，直接清除
    }

    /** 长按词块：与右侧块合并（无右侧时与左侧合并） */
    fun mergeChip(doc: DocxDocument, paraIdx: Int, chipIdx: Int) {
        val chips = chipsOf(doc, paraIdx)
        when {
            chipIdx < chips.size - 1 -> mergePair(doc, paraIdx, chips, chipIdx, chipIdx + 1)
            chipIdx > 0 -> mergePair(doc, paraIdx, chips, chipIdx - 1, chipIdx)
        }
    }

    private fun mergePair(
        doc: DocxDocument,
        paraIdx: Int,
        chips: List<IntRange>,
        a: Int,
        b: Int,
    ) {
        val para = doc.paragraphs.getOrNull(paraIdx) ?: return
        val runA = para.runs[para.runIndexAt(chips[a].first)]
        val runB = para.runs[para.runIndexAt(chips[b].first)]
        if (!runA.editable || !runB.editable) {
            errorMsg = "受保护的词块不能合并"
            return
        }
        val out = ArrayList<IntRange>(chips.size - 1)
        chips.forEachIndexed { i, r ->
            when (i) {
                a -> out.add(r.first..chips[b].last)
                b -> {}
                else -> out.add(r)
            }
        }
        chipOverrides[paraIdx] = out
        selChips = emptySet()
    }

    fun selectionRange(doc: DocxDocument): Pair<Int, Int>? {
        val para = doc.paragraphs.getOrNull(selPara) ?: return null
        if (selChips.isEmpty()) return null
        val chips = chipsOf(doc, selPara)
        val idxs = selChips.filter { it in chips.indices }
        if (idxs.isEmpty()) return null
        val start = chips[idxs.min()].first
        val end = chips[idxs.max()].last + 1
        return start to end
    }

    fun firstSelFmt(doc: DocxDocument): RunFmt? {
        val range = selectionRange(doc) ?: return null
        val para = doc.paragraphs[selPara]
        for ((k, run) in para.runs.withIndex()) {
            if (!run.editable) continue
            val rs = para.runTextStarts[k]
            if (rs + run.text.length > range.first && rs < range.second) {
                return RprOps.readFmt(RprOps.parseChildren(run.rprXml))
            }
        }
        return null
    }

    fun selectAllEditableInPara(doc: DocxDocument) {
        val para = doc.paragraphs.getOrNull(selPara) ?: return
        val chips = chipsOf(doc, selPara)
        selChips = chips.indices
            .filter { ci -> para.runs[para.runIndexAt(chips[ci].first)].editable }
            .toSet()
    }

    fun applyPatch(doc: DocxDocument, patch: FontPatch) {
        val range = selectionRange(doc) ?: return
        val cur = xml ?: return
        undoStack.add(cur)
        if (undoStack.size > 50) undoStack.removeAt(0)
        xml = DocxEditor.applyFormat(doc, selPara, range.first, range.second, patch)
        // 保留选中：连续调节字号/换色不断档，由"取消"按钮手动清除
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            xml = undoStack.removeAt(undoStack.size - 1)
            selChips = emptySet()
        }
    }
}

@Composable
fun ZhaZiApp(state: EditorState) {
    val scheme = darkColorScheme(
        primary = Color(0xFF3D7EFF),
        background = Color(0xFF141414),
        surface = Color(0xFF141414),
        surfaceVariant = Color(0xFF232323),
        onSurface = Color(0xFFE6E6E6),
        onSurfaceVariant = Color(0xFFAAAAAA),
    )
    MaterialTheme(colorScheme = scheme) {
        val activity = LocalContext.current as MainActivity
        val snack = remember { SnackbarHostState() }
        val doc = remember(state.xml) { state.xml?.let { DocxParser.parse(it) } }

        LaunchedEffect(state.errorMsg) {
            state.errorMsg?.let {
                snack.showSnackbar(it)
                state.errorMsg = null
            }
        }

        val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) activity.loadFromUri(uri)
        }
        val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocxZip.MIME)) { uri ->
            if (uri != null) activity.saveTo(uri)
        }

        val openAction = { openDoc.launch(arrayOf(DocxZip.MIME)) }

        Scaffold(
            containerColor = scheme.background,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                TopBar(
                    state,
                    onOpen = openAction,
                    onSave = {
                        if (state.xml == null) state.errorMsg = "请先打开一个 .docx"
                        else saveDoc.launch(state.fileName.ifBlank { "炸字文档.docx" })
                    },
                    onUndo = { state.undo() },
                    onClose = { state.close() },
                )
            },
            bottomBar = {
                if (doc != null && state.selChips.isNotEmpty()) {
                    FormatBar(
                        selCount = state.selChips.size,
                        fmt = state.firstSelFmt(doc),
                        brushActive = state.brushActive,
                        onToggleBrush = {
                            if (state.brushActive) state.stopBrush() else state.startBrush(doc)
                        },
                        onPatch = { state.applyPatch(doc, it) },
                        onClearSel = { state.selChips = emptySet() },
                        onSelectAllPara = { state.selectAllEditableInPara(doc) },
                    )
                }
            },
        ) { pad ->
            if (doc == null) {
                Welcome(openAction)
            } else {
                LazyColumn(
                    Modifier.padding(pad).fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "点选词块 → 底部改格式 · 双击拆成单字 · 长按与右块合并",
                            color = Color(0xFF777777),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    items(doc.paragraphs.size, key = { it }) { pi ->
                        ParaCard(state, doc, pi)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: EditorState, onOpen: () -> Unit, onSave: () -> Unit, onUndo: () -> Unit, onClose: () -> Unit) {
    Surface(color = Color(0xFF1B1B1B), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.fileName.ifBlank { "炸字编辑" },
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo, enabled = state.undoStack.isNotEmpty()) { Text("撤销") }
            TextButton(onClick = onClose, enabled = state.xml != null) { Text("关闭") }
            TextButton(onClick = onOpen) { Text("打开") }
            Button(onClick = onSave) { Text("保存") }
        }
    }
}

@Composable
private fun ParaCard(state: EditorState, doc: DocxDocument, pi: Int) {
    val para = doc.paragraphs[pi]
    val chips = remember(para.text, state.chipOverrides[pi]) {
        state.chipOverrides[pi] ?: Segmenter.chips(para.text)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(10.dp)) {
            if (para.text.isEmpty()) {
                Spacer(Modifier.height(8.dp))
            } else {
                ParaChips(
                    para = para,
                    chips = chips,
                    selChips = if (state.selPara == pi) state.selChips else emptySet(),
                    onToggle = { ci ->
                        if (state.brushActive) state.applyBrushToChip(doc, pi, ci)
                        else state.toggleChip(pi, ci)
                    },
                    onSplit = { state.splitChip(doc, pi, it) },
                    onMerge = { state.mergeChip(doc, pi, it) },
                )
            }
        }
    }
}

@Composable
private fun Welcome(onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("炸字编辑", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(
            "把 Word 文档炸成一个个词块：\n点选词块，即可快速修改字体、字号、颜色，\n告别手机上难用的长按拖拽选字。",
            color = Color(0xFF999999),
            fontSize = 15.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(36.dp))
        Button(onClick = onOpen) { Text("打开 .docx 文档") }
        Spacer(Modifier.height(12.dp))
        Text("文档仅在本机处理，不会上传", color = Color(0xFF666666), fontSize = 12.sp)
    }
}

private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
