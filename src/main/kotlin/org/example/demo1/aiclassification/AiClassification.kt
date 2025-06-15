package org.example.demo1.aiclassification
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.*
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import org.example.demo1.toolWindow.ClassificationResultPanel
import org.example.demo1.toolWindow.ClassificationDisplayItem
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets


class AiClassification : AnAction() {


    private var codesArray = mutableListOf<String>()
    private var codesMethodNames = mutableListOf<String>()
    private var codesArrayStartLocation = mutableListOf<Int>()
    private var codesArrayEndLocation = mutableListOf<Int>()
    private var results = mutableListOf<ClassificationResult>()


    override fun actionPerformed(e: AnActionEvent) {

        //clean the attributes at a new scanning
        codesArray.clear()
        codesMethodNames.clear()
        codesArrayStartLocation.clear()
        codesArrayEndLocation.clear()
        results.clear()

        //validations
        val project = e.project ?: run {
            Messages.showErrorDialog("No project found.", "AI Classification Failed")
            return
        }
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: run {
            Messages.showErrorDialog("No file selected.", "AI Classification Failed")
            return
        }

        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

        if (psiFile.fileType.name != "JAVA") {
            Messages.showErrorDialog("Not a java file.", "AI Classification Failed")
            return
        }

        if (hasCodeErrors(project, psiFile)) {
            Messages.showErrorDialog(
                project,
                "The file contains syntax errors. Please fix them before classification.",
                "Code Error Detected"
            )
            return
        }

        if (psiFile is PsiJavaFile) {
            //take all methods from a class
            val methodDataList = collectMethodData(psiFile, document)

            //display progress bar and performe classification
            ProgressManager.getInstance().run(object : Task.Modal(project, "Classifying...", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Preparing classification..."
                    indicator.fraction = 0.0

                    for ((index, methodData) in methodDataList.withIndex()) {
                        if (indicator.isCanceled) return
                        if(methodData.body.length === 0)continue

                        codesArray.add(methodData.text)
                        codesMethodNames.add(methodData.name)
                        codesArrayStartLocation.add(methodData.startLine)
                        codesArrayEndLocation.add(methodData.endLine)

                        indicator.text = "Classifying ${methodData.name}..."
                        indicator.fraction = index.toDouble() / methodDataList.size

                        try {
                            val result = performAIClassification(methodData.text)
                            results.add(result)
                        } catch (ex: Exception) {
                            throw RuntimeException("Failed to classify ${methodData.name}: ${ex.message}", ex)
                        }
                    }
                }

                override fun onSuccess() {
                    ApplicationManager.getApplication().invokeLater {
                        //access the panel
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("DetectiveWindow")
                        val content = toolWindow?.contentManager?.contents?.firstOrNull()
                        val panel = content?.component as? ClassificationResultPanel

                        panel?.lastProcessedClassificationFile = file
                        //put the start/end of lines in Item so it can be used later and other information
                        val displayResults = codesArray.indices.map { i ->
                            val startOffset = document.getLineStartOffset(codesArrayStartLocation[i] - 1)
                            val endOffset = document.getLineEndOffset(codesArrayEndLocation[i] - 1)
                            val marker = document.createRangeMarker(startOffset, endOffset)

                            ClassificationDisplayItem(
                                file = file.path,
                                methodName = codesMethodNames[i],
                                label = results[i].label,
                                confidence = results[i].confidence,
                                startLine = codesArrayStartLocation[i],
                                endLine = codesArrayEndLocation[i],
                                valid = true,
                                rangeMarker = marker
                            )
                        }

                        panel?.updateResults(displayResults)
                        toolWindow?.show()
                    }
                }

                override fun onThrowable(error: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Classification failed: ${error.message}", "Error")
                    }
                }
            })
        }
    }

    private fun collectMethodData(psiFile: PsiJavaFile, document: Document): List<MethodData> {
        //mapping the method attributes to the class I made
        return ReadAction.compute<List<MethodData>, Throwable> {
            val methods = psiFile.classes.flatMap { it.methods.toList() }
            methods.map { method ->
                val methodName = method.name
                val methodText = method.text
                val startLine = document.getLineNumber(method.startOffset) + 1
                val endLine = document.getLineNumber(method.endOffset) + 1
                val bodyStatements = method.body?.statements
                val bodyText = bodyStatements?.joinToString("\n") { it.text } ?: ""

                MethodData(methodName, methodText, startLine, endLine,bodyText)
            }
        }
    }




     fun performAIClassification(code: String): ClassificationResult {
        val url = URL("http://localhost:5000/classify")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 10000  // 10 seconds timeout
        connection.readTimeout = 30000     // 30 seconds read timeout

        val jsonInputString = JSONObject().apply {
            put("text", code)
        }.toString()

        try {
            //opening an output stream and sending the text over it
            connection.outputStream.use { outStream ->
                val input = jsonInputString.toByteArray(StandardCharsets.UTF_8)
                outStream.write(input, 0, input.size)
            }
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                //println("API Response: $response")

                //make the response json format again and take the info out of it
                val jsonResponse = JSONObject(response)
                val label = jsonResponse.getString("label")
                val confidence = jsonResponse.getDouble("confidence")

                return ClassificationResult(label, confidence)
            } else {

                val errorStream = connection.errorStream
                val errorResponse = errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                //println("Error response: $errorResponse")
                throw Exception("API error: $responseCode - $errorResponse")
            }
        } catch (e: Exception) {
            //println("Exception during API call: ${e.message}")
            throw e
        } finally {
            connection.disconnect()
        }
    }

    fun hasCodeErrors(project: Project, psiFile: PsiFile): Boolean {
        val document: Document? = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        if (document != null) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        val errors: List<HighlightInfo> =
            DaemonCodeAnalyzerImpl.getHighlights(document!!, HighlightSeverity.ERROR, project)
        return errors.isNotEmpty()
    }

    data class MethodData(
        val name: String,
        val text: String,
        val startLine: Int,
        val endLine: Int,
        val body: String
    )
}
data class ClassificationResult(
        val label: String,
        val confidence: Double
    )

