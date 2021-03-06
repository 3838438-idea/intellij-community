// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:Suppress("Duplicates")

package com.intellij.configurationStore

import com.intellij.application.options.ReplacePathToMacroMap
import com.intellij.openapi.application.PathMacroFilter
import com.intellij.openapi.components.ComponentManager
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.SystemProperties
import com.intellij.util.xmlb.Constants
import org.jdom.*
import org.jdom.output.Format
import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import javax.xml.transform.Result

private val DEFAULT_FORMAT = JDOMUtil.createFormat("\n")

// expandEmptyElements is ignored
open class JbXmlOutputter @JvmOverloads constructor(lineSeparator: String = "\n",
                                                    private val elementFilter: JDOMUtil.ElementOutputFilter? = null,
                                                    private val macroMap: ReplacePathToMacroMap? = null,
                                                    private val macroFilter: PathMacroFilter? = null,
                                                    private val isForbidSensitiveData: Boolean = true,
                                                    private val storageFilePathForDebugPurposes: String? = null) : BaseXmlOutputter(lineSeparator) {
  companion object {
    @JvmStatic
    @Throws(IOException::class)
    fun collapseMacrosAndWrite(element: Element, project: ComponentManager, writer: Writer) {
      val macroManager = PathMacroManager.getInstance(project)
      JbXmlOutputter(macroMap = macroManager.replacePathMap, macroFilter = macroManager.macroFilter).output(element, writer)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun collapseMacrosAndWrite(element: Element, project: ComponentManager): String {
      val writer = StringWriter()
      collapseMacrosAndWrite(element, project, writer)
      return writer.toString()
    }

    fun escapeElementEntities(str: String?): String {
      return JDOMUtil.escapeText(str!!, false, false)
    }
  }

  // For normal output
  private val format = if (DEFAULT_FORMAT.lineSeparator == lineSeparator) DEFAULT_FORMAT else JDOMUtil.createFormat(lineSeparator)

  /**
   * This will print the `Document` to the given Writer.
   *
   *
   *
   * Warning: using your own Writer may cause the outputter's
   * preferred character encoding to be ignored.  If you use
   * encodings other than UTF-8, we recommend using the method that
   * takes an OutputStream instead.
   *
   *
   * @param doc `Document` to format.
   * @param out `Writer` to use.
   * @throws IOException - if there's any problem writing.
   */
  @Throws(IOException::class)
  fun output(doc: Document, out: Writer) {
    printDeclaration(out, format.encoding)

    // Print out root element, as well as any root level comments and processing instructions, starting with no indentation
    val content = doc.content
    for (obj in content) {
      when (obj) {
        is Element -> printElement(out, doc.rootElement, 0)
        is Comment -> printComment(out, obj)
        is ProcessingInstruction -> printProcessingInstruction(out, obj)
        is DocType -> {
          printDocType(out, doc.docType)
          // Always print line separator after declaration, helps the
          // output look better and is semantically inconsequential
          writeLineSeparator(out)
        }
      }

      newline(out)
      indent(out, 0)
    }

    // Output final line separator
    // We output this no matter what the newline flags say
    writeLineSeparator(out)

    out.flush()
  }

  @Throws(IOException::class)
  private fun writeLineSeparator(out: Writer) {
    if (format.lineSeparator != null) {
      out.write(format.lineSeparator)
    }
  }

  /**
   * Print out the `[DocType]`.
   *
   * @param doctype `DocType` to output.
   * @param out     `Writer` to use.
   */
  @Throws(IOException::class)
  fun output(doctype: DocType, out: Writer) {
    printDocType(out, doctype)
    out.flush()
  }

  @Throws(IOException::class)
  fun output(element: Element, out: Writer) {
    printElement(out, element, 0)
  }

  /**
   * This will handle printing of the declaration.
   * Assumes XML version 1.0 since we don't directly know.
   *
   * @param out      `Writer` to use.
   * @param encoding The encoding to add to the declaration
   */
  @Throws(IOException::class)
  private fun printDeclaration(out: Writer, encoding: String) {
    // Only print the declaration if it's not being omitted
    if (!format.omitDeclaration) {
      // Assume 1.0 version
      out.write("<?xml version=\"1.0\"")
      if (!format.omitEncoding) {
        out.write(" encoding=\"$encoding\"")
      }
      out.write("?>")

      // Print new line after decl always, even if no other new lines
      // Helps the output look better and is semantically
      // inconsequential
      writeLineSeparator(out)
    }
  }

  /**
   * This will handle printing of processing instructions.
   *
   * @param pi  `ProcessingInstruction` to write.
   * @param out `Writer` to use.
   */
  @Throws(IOException::class)
  private fun printProcessingInstruction(out: Writer, pi: ProcessingInstruction) {
    val target = pi.target
    var piProcessed = false

    if (!format.ignoreTrAXEscapingPIs) {
      if (target == Result.PI_DISABLE_OUTPUT_ESCAPING) {
        piProcessed = true
      }
      else if (target == Result.PI_ENABLE_OUTPUT_ESCAPING) {
        piProcessed = true
      }
    }
    if (!piProcessed) {
      writeProcessingInstruction(out, pi, target)
    }
  }

  /**
   * This will handle printing of `[CDATA]` text.
   *
   * @param cdata `CDATA` to output.
   * @param out   `Writer` to use.
   */
  @Throws(IOException::class)
  private fun printCDATA(out: Writer, cdata: CDATA) {
    var str: String
    if (format.textMode == Format.TextMode.NORMALIZE) {
      str = cdata.textNormalize
    }
    else {
      str = cdata.text
      if (format.textMode == Format.TextMode.TRIM) {
        str = str.trim()
      }
    }
    out.write("<![CDATA[")
    out.write(str)
    out.write("]]>")
  }

  /**
   * This will handle printing a string.  Escapes the element entities,
   * trims interior whitespace, etc. if necessary.
   */
  @Throws(IOException::class)
  private fun printString(out: Writer, str: String?) {
    var normalizedString = str
    if (format.textMode == Format.TextMode.NORMALIZE) {
      normalizedString = Text.normalizeString(normalizedString)
    }
    else if (format.textMode == Format.TextMode.TRIM) {
      normalizedString = normalizedString!!.trim()
    }

    if (macroMap != null) {
      normalizedString = macroMap.substitute(normalizedString, SystemInfoRt.isFileSystemCaseSensitive)
    }
    out.write(escapeElementEntities(normalizedString))
  }

  /**
   * This will handle printing of a `[Element]`,
   * its `[Attribute]`s, and all contained (child)
   * elements, etc.
   *
   * @param element `Element` to output.
   * @param out     `Writer` to use.
   * @param level   `int` level of indention.
   */
  @Throws(IOException::class)
  fun printElement(out: Writer, element: Element, level: Int) {
    if (elementFilter != null && !elementFilter.accept(element, level)) {
      return
    }

    // Print the beginning of the tag plus attributes and any
    // necessary namespace declarations
    out.write('<'.toInt())
    printQualifiedName(out, element)

    if (element.hasAttributes()) {
      printAttributes(out, element.attributes)
    }

    // depending on the settings (newlines, textNormalize, etc), we may or may not want to print all of the content,
    // so determine the index of the start of the content we're interested in based on the current settings.
    if (!writeContent(out, element, level)) {
      return
    }

    out.write("</")
    printQualifiedName(out, element)
    out.write('>'.toInt())
  }

  @Throws(IOException::class)
  protected open fun writeContent(out: Writer, element: Element, level: Int): Boolean {
    if (isForbidSensitiveData) {
      checkIsElementContainsSensitiveInformation(element)
    }

    val content = element.content
    val start = skipLeadingWhite(content, 0)
    val size = content.size
    if (start >= size) {
      // content is empty or all insignificant whitespace
      out.write(" />")
      return false
    }

    out.write('>'.toInt())

    // for a special case where the content is only CDATA or Text we don't want to indent after the start or before the end tag
    if (nextNonText(content, start) < size) {
      // case Mixed Content - normal indentation
      newline(out)
      printContentRange(out, content, start, size, level + 1)
      newline(out)
      indent(out, level)
    }
    else {
      // case all CDATA or Text - no indentation
      printTextRange(out, content, start, size)
    }
    return true
  }

  /**
   * This will handle printing of content within a given range.
   * The range to print is specified in typical Java fashion; the
   * starting index is inclusive, while the ending index is
   * exclusive.
   *
   * @param content `List` of content to output
   * @param start   index of first content node (inclusive.
   * @param end     index of last content node (exclusive).
   * @param out     `Writer` to use.
   * @param level   `int` level of indentation.
   */
  @Throws(IOException::class)
  private fun printContentRange(out: Writer, content: List<Content>, start: Int, end: Int, level: Int) {
    var firstNode: Boolean // Flag for 1st node in content
    var next: Content       // Node we're about to print
    var first: Int
    var index: Int  // Indexes into the list of content

    index = start
    while (index < end) {
      firstNode = index == start
      next = content[index]

      // Handle consecutive CDATA, Text, and EntityRef nodes all at once
      if (next is Text || next is EntityRef) {
        first = skipLeadingWhite(content, index)
        // Set index to next node for loop
        index = nextNonText(content, first)

        // If it's not all whitespace - print it!
        if (first < index) {
          if (!firstNode) {
            newline(out)
          }
          indent(out, level)
          printTextRange(out, content, first, index)
        }
        continue
      }

      // Handle other nodes
      if (!firstNode) {
        newline(out)
      }

      indent(out, level)

      when (next) {
        is Comment -> printComment(out, next)
        is Element -> printElement(out, next, level)
        is ProcessingInstruction -> printProcessingInstruction(out, next)
        else -> {
          // XXX if we get here then we have a illegal content, for now we'll just ignore it (probably should throw a exception)
        }
      }

      index++
    }
  }

  /**
   * This will handle printing of a sequence of `[CDATA]`
   * or `[Text]` nodes.  It is an error to have any other
   * pass this method any other type of node.
   *
   * @param content `List` of content to output
   * @param start   index of first content node (inclusive).
   * @param end     index of last content node (exclusive).
   * @param out     `Writer` to use.
   */
  @Throws(IOException::class)
  private fun printTextRange(out: Writer, content: List<Content>, start: Int, end: Int) {
    @Suppress("NAME_SHADOWING")
    val start = skipLeadingWhite(content, start)
    if (start >= content.size) {
      return
    }

    // and remove trialing whitespace-only nodes
    @Suppress("NAME_SHADOWING")
    val end = skipTrailingWhite(content, end)

    var previous: String? = null
    for (i in start until end) {
      val node = content[i]

      // get the unmangled version of the text we are about to print
      val next: String?
      when (node) {
        is Text -> next = node.text
        is EntityRef -> next = "&" + node.getValue() + ";"
        else -> throw IllegalStateException("Should see only CDATA, Text, or EntityRef")
      }

      if (next.isNullOrEmpty()) {
        continue
      }

      // determine if we need to pad the output (padding is only need in trim or normalizing mode)
      if (previous != null && (format.textMode == Format.TextMode.NORMALIZE || format.textMode == Format.TextMode.TRIM)) {
        if (endsWithWhite(previous) || startsWithWhite(next!!)) {
          out.write(' '.toInt())
        }
      }

      // print the node
      when (node) {
        is CDATA -> printCDATA(out, node)
        is EntityRef -> printEntityRef(out, node)
        else -> printString(out, next)
      }

      previous = next
    }
  }

  /**
   * This will handle printing of a `[Attribute]` list.
   *
   * @param attributes `List` of Attribute objects
   * @param out        `Writer` to use
   */
  @Throws(IOException::class)
  private fun printAttributes(out: Writer, attributes: List<Attribute>) {
    for (attribute in attributes) {
      out.write(' '.toInt())
      printQualifiedName(out, attribute)
      out.write('='.toInt())
      out.write('"'.toInt())

      val value = if (macroMap != null && (macroFilter == null || !macroFilter.skipPathMacros(attribute))) {
        macroMap.getAttributeValue(attribute, macroFilter, SystemInfoRt.isFileSystemCaseSensitive, false)
      }
      else {
        attribute.value
      }

      if (isForbidSensitiveData && BaseXmlOutputter.isNameIndicatesSensitiveInformation(attribute.name)) {
        logSensitiveInformationError(attribute.name, "Attribute")
      }

      out.write(escapeAttributeEntities(value))
      out.write('"'.toInt())
    }
  }

  /**
   * This will print a newline only if indent is not null.
   *
   * @param out `Writer` to use
   */
  @Throws(IOException::class)
  private fun newline(out: Writer) {
    if (format.indent != null) {
      writeLineSeparator(out)
    }
  }

  /**
   * This will print indents only if indent is not null or the empty string.
   *
   * @param out   `Writer` to use
   * @param level current indent level
   */
  @Throws(IOException::class)
  private fun indent(out: Writer, level: Int) {
    if (format.indent.isNullOrEmpty()) {
      return
    }

    for (i in 0 until level) {
      out.write(format.indent)
    }
  }

  // Returns the index of the first non-all-whitespace CDATA or Text,
  // index = content.size() is returned if content contains
  // all whitespace.
  // @param start index to begin search (inclusive)
  private fun skipLeadingWhite(content: List<Content>, start: Int): Int {
    var index = start
    if (index < 0) {
      index = 0
    }

    val size = content.size
    val textMode = format.textMode
    if (textMode == Format.TextMode.TRIM_FULL_WHITE || textMode == Format.TextMode.NORMALIZE || textMode == Format.TextMode.TRIM) {
      while (index < size) {
        if (!isAllWhitespace(content[index])) {
          return index
        }
        index++
      }
    }
    return index
  }

  // Return the index + 1 of the last non-all-whitespace CDATA or
  // Text node,  index < 0 is returned
  // if content contains all whitespace.
  // @param start index to begin search (exclusive)
  private fun skipTrailingWhite(content: List<Content>, start: Int): Int {
    var index = start
    val size = content.size
    if (index > size) {
      index = size
    }

    val textMode = format.textMode
    if (textMode == Format.TextMode.TRIM_FULL_WHITE || textMode == Format.TextMode.NORMALIZE || textMode == Format.TextMode.TRIM) {
      while (index >= 0) {
        if (!isAllWhitespace(content[index - 1])) {
          break
        }
        --index
      }
    }
    return index
  }

  private fun checkIsElementContainsSensitiveInformation(element: Element) {
    var name: String? = element.name

    @Suppress("SpellCheckingInspection")
    if (BaseXmlOutputter.isNameIndicatesSensitiveInformation(name!!)) {
      logSensitiveInformationError(name, "Element")
    }

    // checks only option tag
    name = element.getAttributeValue(Constants.NAME)
    if (name != null && BaseXmlOutputter.isNameIndicatesSensitiveInformation(name) && element.getAttribute("value") != null) {
      logSensitiveInformationError(name, "Element")
    }
  }

  private fun logSensitiveInformationError(name: String, elementKind: String) {
    var message = "$elementKind \"$name\" probably contains sensitive information"
    if (storageFilePathForDebugPurposes != null) {
      message += " (file: ${storageFilePathForDebugPurposes.replace(FileUtil.toSystemIndependentName(SystemProperties.getUserHome()), "~")})"
    }
    Logger.getInstance(JbXmlOutputter::class.java).error(message)
  }
}

// Return the next non-CDATA, non-Text, or non-EntityRef node,
// index = content.size() is returned if there is no more non-CDATA,
// non-Text, or non-EntryRef nodes
// @param start index to begin search (inclusive)
private fun nextNonText(content: List<Content>, start: Int): Int {
  var index = start
  if (index < 0) {
    index = 0
  }

  val size = content.size
  while (index < size) {
    val node = content[index]
    if (!(node is Text || node is EntityRef)) {
      return index
    }
    index++
  }
  return size
}

private fun printEntityRef(out: Writer, entity: EntityRef) {
  out.write("&")
  out.write(entity.name)
  out.write(";")
}

private fun printComment(out: Writer, comment: Comment) {
  out.write("<!--")
  out.write(comment.text)
  out.write("-->")
}

private fun isAllWhitespace(obj: Content): Boolean {
  val str: String
  if (obj is Text) {
    str = obj.text
  }
  else {
    return false
  }

  for (i in 0 until str.length) {
    if (!Verifier.isXMLWhitespace(str[i])) {
      return false
    }
  }
  return true
}

private fun startsWithWhite(str: String): Boolean {
  return !StringUtil.isEmpty(str) && Verifier.isXMLWhitespace(str[0])
}

// Determine if a string ends with a XML whitespace.
private fun endsWithWhite(str: String): Boolean {
  return !StringUtil.isEmpty(str) && Verifier.isXMLWhitespace(str[str.length - 1])
}

private fun escapeAttributeEntities(str: String): String {
  return JDOMUtil.escapeText(str, false, true)
}

@Throws(IOException::class)
private fun printQualifiedName(out: Writer, e: Element) {
  if (!e.namespace.prefix.isEmpty()) {
    out.write(e.namespace.prefix)
    out.write(':'.toInt())
  }
  out.write(e.name)
}

// Support method to print a name without using att.getQualifiedName()
// and thus avoiding a StringBuffer creation and memory churn
@Throws(IOException::class)
private fun printQualifiedName(out: Writer, a: Attribute) {
  val prefix = a.namespace.prefix
  if (!StringUtil.isEmpty(prefix)) {
    out.write(prefix)
    out.write(':'.toInt())
  }
  out.write(a.name)
}