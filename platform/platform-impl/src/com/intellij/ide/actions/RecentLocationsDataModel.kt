// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.actions

import com.intellij.codeInsight.breadcrumbs.FileBreadcrumbsCollector
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.ide.actions.RecentLocationsAction.EMPTY_FILE_TEXT
import com.intellij.ide.actions.RecentLocationsAction.showChanged
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorSettings
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.highlighter.LightHighlighterClient
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.fileEditor.impl.IdeDocumentHistoryImpl
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.breadcrumbs.Crumb
import com.intellij.util.CollectConsumer
import com.intellij.util.DocumentUtil
import com.intellij.util.containers.ContainerUtil
import java.util.*
import java.util.stream.Collectors
import javax.swing.ScrollPaneConstants

data class RecentLocationsDataModel(val project: Project, val editorsToRelease: ArrayList<Editor> = arrayListOf()) {

  private val navigationPlaces: List<RecentLocationItem> by lazy {
    calculateItems(project, false)
  }

  private val changedPlaces: List<RecentLocationItem> by lazy {
    calculateItems(project, true)
  }

  private val navigationPlacesBreadcrumbsMap: Map<IdeDocumentHistoryImpl.PlaceInfo, String> by lazy {
    collectBreadcrumbs(project, navigationPlaces)
  }

  private val changedPlacedBreadcrumbsMap: Map<IdeDocumentHistoryImpl.PlaceInfo, String> by lazy {
    collectBreadcrumbs(project, changedPlaces)
  }

  fun getPlaces(changed: Boolean): List<RecentLocationItem> {
    return if (changed) changedPlaces else navigationPlaces
  }

  fun getBreadcrumbsMap(changed: Boolean): Map<IdeDocumentHistoryImpl.PlaceInfo, String> {
    return if (changed) changedPlacedBreadcrumbsMap else navigationPlacesBreadcrumbsMap
  }

  private fun collectBreadcrumbs(project: Project, items: List<RecentLocationItem>): Map<IdeDocumentHistoryImpl.PlaceInfo, String> {
    return items.stream()
      .map(RecentLocationItem::info)
      .collect(Collectors.toMap({ it }, { getBreadcrumbs(project, it) }))
  }

  private fun getBreadcrumbs(project: Project, placeInfo: IdeDocumentHistoryImpl.PlaceInfo): String {
    val rangeMarker = RecentLocationManager.getInstance(project).getPositionOffset(placeInfo, showChanged(project))
    val fileName = placeInfo.file.name
    if (rangeMarker == null) {
      return fileName
    }

    val collector = FileBreadcrumbsCollector.findBreadcrumbsCollector(project, placeInfo.file)
    var breadcrumbs: Collection<Iterable<Crumb>> = emptyList()
    if (collector != null) {
      val consumer = CollectConsumer<Iterable<Crumb>>()
      collector.updateCrumbs(placeInfo.file,
                             rangeMarker.document,
                             rangeMarker.startOffset,
                             ProgressIndicatorBase(),
                             consumer,
                             true)
      breadcrumbs = consumer.result
    }

    if (breadcrumbs.isEmpty()) {
      return fileName
    }

    val crumbs = ContainerUtil.getFirstItem(breadcrumbs) ?: return fileName
    val breadcrumbsText = StringUtil.join(ContainerUtil.map(crumbs) { crumb -> crumb.text }, " > ")

    return StringUtil.shortenTextWithEllipsis(breadcrumbsText, 50, 0)
  }

  private fun calculateItems(project: Project, changed: Boolean): List<RecentLocationItem> {
    val items = getPlaceLinePairs(project, changed)
    editorsToRelease.addAll(ContainerUtil.map<RecentLocationItem, EditorEx>(items) { item -> item.editor })

    return items
  }

  private fun getPlaceLinePairs(project: Project, changed: Boolean): List<RecentLocationItem> {
    val places = getPlaces(project, changed)
    if (places.isEmpty()) {
      return emptyList()
    }

    val items = arrayListOf<RecentLocationItem>()
    for (placeInfo in places) {
      val editor = createEditor(project, placeInfo)
      if (editor != null) {
        items.add(RecentLocationItem(editor, placeInfo))
      }
    }

    return items
  }

  private fun getPlaces(project: Project, changed: Boolean): List<IdeDocumentHistoryImpl.PlaceInfo> {
    val infos = ContainerUtil.reverse(
      if (changed) IdeDocumentHistory.getInstance(project).changePlaces else IdeDocumentHistory.getInstance(project).backPlaces)

    val infosCopy = arrayListOf<IdeDocumentHistoryImpl.PlaceInfo>()
    for (info in infos) {
      if (infosCopy.stream().noneMatch { info1 -> IdeDocumentHistoryImpl.isSame(info, info1) }) {
        infosCopy.add(info)
      }
    }

    return infosCopy
  }

  private fun createEditor(project: Project, placeInfo: IdeDocumentHistoryImpl.PlaceInfo): EditorEx? {
    val positionOffset = RecentLocationManager.getInstance(project).getPositionOffset(placeInfo, showChanged(project))
    if (positionOffset == null || !positionOffset.isValid) {
      return null
    }
    assert(positionOffset.startOffset == positionOffset.endOffset)

    val fileDocument = positionOffset.document
    val lineNumber = fileDocument.getLineNumber(positionOffset.startOffset)
    val actualTextRange = getTrimmedRange(fileDocument, lineNumber)
    var documentText = fileDocument.getText(actualTextRange)
    if (actualTextRange.isEmpty) {
      documentText = EMPTY_FILE_TEXT
    }

    val editorFactory = EditorFactory.getInstance()
    val editorDocument = editorFactory.createDocument(documentText)
    val editor = editorFactory.createEditor(editorDocument, project) as EditorEx

    val gutterComponentEx = editor.gutterComponentEx
    val linesShift = fileDocument.getLineNumber(actualTextRange.startOffset)
    gutterComponentEx.setLineNumberConvertor { index -> index + linesShift }
    gutterComponentEx.setPaintBackground(false)
    val scrollPane = editor.scrollPane
    scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER

    fillEditorSettings(editor.settings)
    setHighlighting(project, editor, fileDocument, placeInfo, actualTextRange)

    return editor
  }

  private fun fillEditorSettings(settings: EditorSettings) {
    settings.isLineNumbersShown = true
    settings.isCaretRowShown = false
    settings.isLineMarkerAreaShown = false
    settings.isFoldingOutlineShown = false
    settings.additionalColumnsCount = 0
    settings.additionalLinesCount = 0
    settings.isRightMarginShown = false
  }

  private fun setHighlighting(project: Project,
                              editor: EditorEx,
                              document: Document,
                              placeInfo: IdeDocumentHistoryImpl.PlaceInfo,
                              textRange: TextRange) {
    val colorsScheme = EditorColorsManager.getInstance().globalScheme

    applySyntaxHighlighting(project, editor, document, colorsScheme, textRange, placeInfo)
    applyHighlightingPasses(project, editor, document, colorsScheme, textRange)
  }

  private fun applySyntaxHighlighting(project: Project,
                                      editor: EditorEx,
                                      document: Document,
                                      colorsScheme: EditorColorsScheme,
                                      textRange: TextRange,
                                      placeInfo: IdeDocumentHistoryImpl.PlaceInfo) {
    val editorHighlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(placeInfo.file, colorsScheme, project)
    editorHighlighter.setEditor(LightHighlighterClient(document, project))
    editorHighlighter.setText(document.getText(TextRange.create(0, textRange.endOffset)))
    val startOffset = textRange.startOffset
    val iterator = editorHighlighter.createIterator(startOffset)

    while (!iterator.atEnd() && iterator.end <= textRange.endOffset) {
      if (iterator.start >= startOffset) {
        editor.markupModel.addRangeHighlighter(iterator.start - startOffset,
                                               iterator.end - startOffset,
                                               HighlighterLayer.SYNTAX - 1,
                                               iterator.textAttributes,
                                               HighlighterTargetArea.EXACT_RANGE)
      }

      iterator.advance()
    }
  }

  private fun applyHighlightingPasses(project: Project,
                                      editor: EditorEx,
                                      document: Document,
                                      colorsScheme: EditorColorsScheme,
                                      rangeMarker: TextRange) {
    val startOffset = rangeMarker.startOffset
    val endOffset = rangeMarker.endOffset
    DaemonCodeAnalyzerEx.processHighlights(document, project, null, startOffset, endOffset) { info ->
      if (info.startOffset < startOffset || info.endOffset > endOffset) {
        return@processHighlights true
      }

      if (info.severity != HighlightSeverity.INFORMATION) {
        return@processHighlights true
      }

      editor.markupModel.addRangeHighlighter(
        info.actualStartOffset - rangeMarker.startOffset, info.actualEndOffset - rangeMarker.startOffset,
        HighlighterLayer.SYNTAX,
        colorsScheme.getAttributes(info.forcedTextAttributesKey),
        HighlighterTargetArea.EXACT_RANGE)

      true
    }
  }

  private fun getTrimmedRange(document: Document, lineNumber: Int): TextRange {
    val range = getLinesRange(document, lineNumber)
    val text = document.getText(TextRange.create(range.startOffset, range.endOffset))

    val newLinesBefore = StringUtil.countNewLines(
      Objects.requireNonNull<String>(StringUtil.substringBefore(text, StringUtil.trimLeading(text))))
    val newLinesAfter = StringUtil.countNewLines(
      Objects.requireNonNull<String>(StringUtil.substringAfter(text, StringUtil.trimTrailing(text))))

    val firstLine = document.getLineNumber(range.startOffset)
    val firstLineAdjusted = firstLine + newLinesBefore

    val lastLine = document.getLineNumber(range.endOffset)
    val lastLineAdjusted = lastLine - newLinesAfter

    val startOffset = document.getLineStartOffset(firstLineAdjusted)
    val endOffset = document.getLineEndOffset(lastLineAdjusted)

    return TextRange.create(startOffset, endOffset)
  }

  private fun getLinesRange(document: Document, line: Int): TextRange {
    val lineCount = document.lineCount
    if (lineCount == 0) {
      return TextRange.EMPTY_RANGE
    }

    val beforeAfterLinesCount = Registry.intValue("recent.locations.lines.before.and.after", 2)

    val before = Math.min(beforeAfterLinesCount, line)
    val after = Math.min(beforeAfterLinesCount, lineCount - line)

    val linesBefore = before + beforeAfterLinesCount - after
    val linesAfter = after + beforeAfterLinesCount - before

    val startLine = Math.max(line - linesBefore, 0)
    val endLine = Math.min(line + linesAfter, lineCount - 1)

    val startOffset = document.getLineStartOffset(startLine)
    val endOffset = document.getLineEndOffset(endLine)

    return if (startOffset <= endOffset)
      TextRange.create(startOffset, endOffset)
    else
      TextRange.create(DocumentUtil.getLineTextRange(document, line))
  }
}

data class RecentLocationItem(val editor: EditorEx, val info: IdeDocumentHistoryImpl.PlaceInfo)