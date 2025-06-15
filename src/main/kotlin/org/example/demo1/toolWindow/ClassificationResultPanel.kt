package org.example.demo1.toolWindow
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.vladsch.flexmark.html.Disposable
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import java.awt.Component
import kotlin.math.max

class ClassificationResultPanel(val project: Project) : JPanel(BorderLayout()), Disposable {

    private val listModel = DefaultListModel<ClassificationDisplayItem>()
    private val resultList = JBList(listModel)

    private var currentHighlighter: RangeHighlighter? = null
    private var currentHighlightListener: DocumentListener? = null

    private val itemListeners = mutableMapOf<ClassificationDisplayItem, Pair<DocumentListener, com.intellij.openapi.editor.Document>>()

    private var highlightDocument: com.intellij.openapi.editor.Document? = null


    public var lastProcessedClassificationFile: VirtualFile? = null


    init {


        val connection = project.messageBus.connect()
        //if the file is closed deselect item
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                resultList.clearSelection()  // Deselect when the file is closed/switched
            }
        })
        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val selected = resultList.selectedValue ?: return@addListSelectionListener
                val editor =
                    FileEditorManager.getInstance(project).selectedTextEditor ?: return@addListSelectionListener
                highlightSelectedItem(editor, selected)
            }
        }

        resultList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val item = value as ClassificationDisplayItem
                foreground = if (!item.valid) JBColor.GRAY else JBColor.foreground()
                text = if (!item.valid) "${item} [INVALID]" else item.toString()
                return component
            }
        }

        add(JBScrollPane(resultList), BorderLayout.CENTER)
    }

    fun updateResults(results: List<ClassificationDisplayItem>) {
        cleanupAllListeners()
        clearCurrentHighlight()
        listModel.clear()

        results.forEach { item ->
            listModel.addElement(item)
            setupItemListener(item)
        }
    }

    /**
     * Clears the current highlight and its associated listener
     */
    private fun clearCurrentHighlight() {
        ApplicationManager.getApplication().runWriteAction {
            currentHighlighter?.let { highlighter ->
                highlighter.dispose()
                currentHighlighter = null
            }
        }

        highlightDocument?.let { doc ->
            currentHighlightListener?.let { listener ->
                ApplicationManager.getApplication().runWriteAction {
                    doc.removeDocumentListener(listener)
                }
            }
        }
        currentHighlightListener = null
        highlightDocument = null
    }

    /**
     * Removes the document listener for a specific item
     */
    private fun removeItemListener(item: ClassificationDisplayItem) {
        itemListeners[item]?.let { (listener, document) ->
            ApplicationManager.getApplication().runWriteAction {
                document.removeDocumentListener(listener)
            }
            itemListeners.remove(item)
        }
    }

    /**
     * Cleans up all item listeners.
     */
    private fun cleanupAllListeners() {
        ApplicationManager.getApplication().runWriteAction {
            itemListeners.values.forEach { (listener, document) ->
                document.removeDocumentListener(listener)
            }
        }

        itemListeners.clear()
    }

    /**
     * Call this method when the panel is being disposed to clean up resources
     */
    override
    fun dispose() {
        cleanupAllListeners()
        clearCurrentHighlight()
    }

    /**
     * Assign the listener the task of checking if the method is changed and the invalidation of the list item
     */
    private fun setupItemListener(item: ClassificationDisplayItem) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document

        val listener = object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                handleItemDocumentChange(item, event)
            }
        }

        itemListeners[item] = Pair(listener, document)
        document.addDocumentListener(listener)
    }


    /**
     * Handles document changes for a specific item
     * Checks if the change affects the item's range and invalidates it if necessary
     */
    private fun handleItemDocumentChange(item: ClassificationDisplayItem, event: DocumentEvent) {
        val rangeMarker = item.rangeMarker ?: return

        if (!rangeMarker.isValid) {
            invalidateItem(item)
            return
        }

        // calculate the range affected by the change
        val changeStart = event.offset
        val changeEnd = changeStart + max(event.oldLength, event.newLength)

        // check if the change overlaps with our item's range
        val affectsItem = changeStart >= rangeMarker.startOffset &&
                changeEnd <= rangeMarker.endOffset
        if (affectsItem) {
            invalidateItem(item)
        }

    }

    /**
     * Invalidates an item and cleans up its resources
     */
    private fun invalidateItem(item: ClassificationDisplayItem) {

        item.valid = false
        item.rangeMarker = null

        if (resultList.selectedValue == item) {
            clearCurrentHighlight()
        }
        removeItemListener(item)
        //update ui
        ApplicationManager.getApplication().invokeLater {
            resultList.repaint()
        }

    }

    private fun highlightSelectedItem(editor: Editor, item: ClassificationDisplayItem) {
        // Get the VirtualFile of the currently opened document
        val currentFile = FileDocumentManager.getInstance().getFile(editor.document) ?: run {
            Messages.showErrorDialog("No file associated with the editor.", "Highlight Error")
            return
        }

        // Check if the current file matches the classified file
        if (currentFile != lastProcessedClassificationFile) {
            Messages.showWarningDialog(
                "The opened file is not the one that was classified. Highlighting disabled.",
                "File Mismatch"
            )
            return
        }

        if (!item.valid) {//skip if invalid
            return
        }

        val rangeMarker = item.rangeMarker ?: return
        clearCurrentHighlight()

        val textAttributes = TextAttributes().apply {
            backgroundColor = if (item.label == "Vulnerable") {
                Color(240, 0, 0, 128)
            } else {
                Color(135, 255, 135, 128)
            }
        }

        ApplicationManager.getApplication().runWriteAction {
            currentHighlighter = editor.markupModel.addRangeHighlighter(
                rangeMarker.startOffset,
                rangeMarker.endOffset,
                0,
                textAttributes,
                com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
            )
        }

        setupHighlightListener(editor, item)

        // move the cursor to the method highlighted
        editor.caretModel.moveToOffset(rangeMarker.startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }


    /**
     * Sets up a listener specifically for the currently highlighted item
     * This is separate from the item listeners and handles highlight specific cleanup
     */
    private fun setupHighlightListener(editor: Editor, item: ClassificationDisplayItem) {
        currentHighlightListener = object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                val rangeMarker = item.rangeMarker ?: return

                val changeStart = event.offset
                val changeEnd = changeStart + max(event.oldLength, event.newLength)
                val affectsHighlight = changeStart <= rangeMarker.endOffset &&
                        changeEnd >= rangeMarker.startOffset

                if (!rangeMarker.isValid || affectsHighlight) {
                    clearCurrentHighlight()
                }
            }
        }

        highlightDocument = editor.document
        highlightDocument?.addDocumentListener(currentHighlightListener!!)
    }
}

data class ClassificationDisplayItem(
    val file: String,
    val methodName: String,
    val label: String,
    val confidence: Double,
    val startLine: Int,
    val endLine: Int,
    var valid: Boolean,
    var rangeMarker: com.intellij.openapi.editor.RangeMarker? = null
) {
    override fun toString(): String {
        return "$file | $methodName | $label (${String.format("%.5f", confidence)}%)"
    }
}