/*******************************************************************************
 * Copyright (c) 2015, 2015 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package melnorme.lang.tooling.ops;

import static melnorme.utilbox.core.Assert.AssertNamespace.assertFail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;

import melnorme.lang.tooling.data.StatusLevel;
import melnorme.utilbox.collections.ArrayList2;
import melnorme.utilbox.core.CommonException;
import melnorme.utilbox.misc.StringUtil;
import melnorme.utilbox.process.ExternalProcessHelper.ExternalProcessResult;


public abstract class BuildOutputParser extends AbstractToolOutputParser<ArrayList<ToolSourceMessage>> {
	
	protected ArrayList2<ToolSourceMessage> buildMessages;
	
	public BuildOutputParser() {
	}
	
	public ArrayList2<ToolSourceMessage> getBuildMessages() {
		return buildMessages;
	}
	
	public ArrayList<ToolSourceMessage> parseOutput(ExternalProcessResult buildResult) throws CommonException {
		return doParse(buildResult);
	}
	@Override
	protected ArrayList<ToolSourceMessage> doParse(ExternalProcessResult result) throws CommonException {
		return parse(result.getStdErrBytes().toString(StringUtil.UTF8));
	}
	
	public ArrayList<ToolSourceMessage> parseMessages(String stderr) throws CommonException {
		return parse(stderr);
	}
	@Override
	protected ArrayList<ToolSourceMessage> parse(String input) throws CommonException {
		StringReader sr = new StringReader(input);
		try {
			return parseMessages(sr);
		} catch (IOException e) {
			throw assertFail();
		}
	}
	
	protected ArrayList<ToolSourceMessage> parseMessages(StringReader sr) throws IOException {
		buildMessages = new ArrayList2<>();
		
		BufferedReader br = new BufferedReader(sr);
		
		while(true) {
			String outputLine = br.readLine();
			if(outputLine == null) {
				break;
			}
			
			doParseLine(outputLine, br);
		}
		
		return buildMessages;
	}
	
	protected abstract void doParseLine(String outputLine, BufferedReader br) throws IOException;
	
	protected void addMessage(String pathString, String lineString, String columnString, String endLineString,
			String endColumnString, String messageTypeString, String message) {
		try {
			buildMessages.add(createMessage(pathString, lineString, columnString, endLineString, endColumnString, 
				messageTypeString, message));
		} catch (CommonException ce) {
			handleLineParseError(ce);
		}
	}
	
	protected void handleUnknownLineSyntax(String line) {
		handleLineParseError(new CommonException("Unknown error line syntax: " + line));
	}
	
	@Override
	protected abstract void handleLineParseError(CommonException ce);
	
	/* -----------------  ----------------- */
	
	protected ToolSourceMessage createMessage(String pathString, String lineString, String columnString, 
			String endLineString, String endColumnString, 
			String messageTypeString, String message) throws CommonException {
		
		Path filePath = parsePath(pathString).normalize();
		int lineNo = parsePositiveInt(lineString);
		int column = parseOptionalPositiveInt(columnString);
		
		int endline = parseOptionalPositiveInt(endLineString);
		int endColumn = parseOptionalPositiveInt(endColumnString);
		
		// due to the REGEXP, can only be WARNING or ERROR
		StatusLevel msgKind = StatusLevel.fromString(messageTypeString);  
		
		SourceLineColumnRange location = new SourceLineColumnRange(filePath, lineNo, column, endline, endColumn);
		return new ToolSourceMessage(location, msgKind, message);
	}
	
	protected StatusLevel parseMessageKind(String messageTypeString) throws CommonException {
		return StatusLevel.fromString(messageTypeString);
	}
	
	protected int parseOptionalPositiveInt(String columnStr) throws CommonException {
		return columnStr == null ? -1 : parsePositiveInt(columnStr);
	}
	
}