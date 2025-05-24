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
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import java.awt.Component
import kotlin.math.max

class ClassificationResultPanel(val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<ClassificationDisplayItem>()
    private val resultList = JBList(listModel)

    private var currentHighlighter: RangeHighlighter? = null
    private var currentHighlightListener: DocumentListener? = null

    private val itemListeners = mutableMapOf<ClassificationDisplayItem, Pair<DocumentListener, com.intellij.openapi.editor.Document>>()

    private var highlightDocument: com.intellij.openapi.editor.Document? = null


    init {
        println("initializare")
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
        println("Updating results with ${results.size} items")

        cleanupAllListeners()
        clearCurrentHighlight()
        listModel.clear()

        // Add new results and set up listeners
        results.forEach { item ->
            listModel.addElement(item)
            setupItemListener(item)
        }
    }

    /**
     * Clears the current highlight and its associated listener.
     */
    private fun clearCurrentHighlight() {
        println("Clearing current highlight")

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
     * Removes the document listener for a specific item.
     */
    private fun removeItemListener(item: ClassificationDisplayItem) {
        itemListeners[item]?.let { (listener, document) ->
            ApplicationManager.getApplication().runWriteAction {
                document.removeDocumentListener(listener)
            }
            itemListeners.remove(item)
            println("Removed listener for item: ${item.methodName}")
        }
    }

    /**
     * Cleans up all item listeners.
     */
    private fun cleanupAllListeners() {
        println("Cleaning up all listeners (${itemListeners.size} items)")

        ApplicationManager.getApplication().runWriteAction {
            itemListeners.values.forEach { (listener, document) ->
                document.removeDocumentListener(listener)
            }
        }

        itemListeners.clear()
    }


    /**
     * Call this method when the panel is being disposed to clean up resources.
     */
    fun dispose() {
        cleanupAllListeners()
        clearCurrentHighlight()
    }

    private fun setupItemListener(item: ClassificationDisplayItem) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document

        val listener = object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                handleItemDocumentChange(item, event)
            }
        }

        // Store listener and its document
        itemListeners[item] = Pair(listener, document)
        document.addDocumentListener(listener)

        println("Set up listener for item: ${item.methodName}")
    }


    /**
     * Handles document changes for a specific item.
     * Checks if the change affects the item's range and invalidates it if necessary.
     */
    private fun handleItemDocumentChange(item: ClassificationDisplayItem, event: DocumentEvent) {
        val rangeMarker = item.rangeMarker ?: return

        if (!rangeMarker.isValid) {
            invalidateItem(item)
            return
        }

        // Calculate the range affected by the change
        val changeStart = event.offset
        val changeEnd = changeStart + max(event.oldLength, event.newLength)

        // Check if the change overlaps with our item's range
        val affectsItem = changeStart <= rangeMarker.endOffset &&
                changeEnd >= rangeMarker.startOffset

        if (affectsItem) {
            println("Document change affects item: ${item.methodName}")
            invalidateItem(item)
        }
    }

    /**
     * Invalidates an item and cleans up its resources.
     */
    private fun invalidateItem(item: ClassificationDisplayItem) {
        println("Invalidating item: ${item.methodName}")

        // Mark item as invalid
        item.valid = false
        item.rangeMarker = null

        // If this item is currently highlighted, clear the highlight
        if (resultList.selectedValue == item) {
            clearCurrentHighlight()
        }

        // Remove the item's listener
        removeItemListener(item)

        // Update the UI
        ApplicationManager.getApplication().invokeLater {
            resultList.repaint()
        }

    }


    private fun highlightSelectedItem(editor: Editor, item: ClassificationDisplayItem) {
        println("Highlighting item: ${item.methodName}")

        if (!item.valid) {
            println("Item is invalid - skipping highlight")
            return
        }

        val rangeMarker = item.rangeMarker ?: return

        // Clear any existing highlight
        clearCurrentHighlight()

        // Create highlight attributes
        val textAttributes = TextAttributes().apply {
            backgroundColor = if (item.label == "Vulnerable") {
                Color(240, 0, 0, 128) // Transparent red
            } else {
                Color(135, 255, 135, 128) // Transparent green
            }
        }

        // Apply highlight
        ApplicationManager.getApplication().runWriteAction {
            currentHighlighter = editor.markupModel.addRangeHighlighter(
                rangeMarker.startOffset,
                rangeMarker.endOffset,
                0,
                textAttributes,
                com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
            )
        }

        // Set up listener for the current highlight
        setupHighlightListener(editor, item)

        // Move cursor and scroll to the highlighted area
        editor.caretModel.moveToOffset(rangeMarker.startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }


    /**
     * Sets up a listener specifically for the currently highlighted item.
     * This is separate from the item listeners and handles highlight-specific cleanup.
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
                    // The highlight will be cleared by the item listener
                    // We just need to remove this highlight listener
                    clearCurrentHighlight()
                }
            }
        }

        highlightDocument = editor.document
        highlightDocument?.addDocumentListener(currentHighlightListener!!)

        //editor.document.addDocumentListener(currentHighlightListener!!)
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