/*
 * Copyright © 2009-2010 Reinier Zwitserloot, Roel Spilker and Robbert Jan Grootjans.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse.handlers;

import static lombok.eclipse.handlers.EclipseHandlerUtil.createNameReference;

import java.util.Arrays;

import lombok.Cleanup;
import lombok.core.AnnotationValues;
import lombok.core.AST.Kind;
import lombok.eclipse.Eclipse;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.Block;
import org.eclipse.jdt.internal.compiler.ast.CaseStatement;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.EqualExpression;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.IfStatement;
import org.eclipse.jdt.internal.compiler.ast.IntLiteral;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MemberValuePair;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.NullLiteral;
import org.eclipse.jdt.internal.compiler.ast.OperatorIds;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.SwitchStatement;
import org.eclipse.jdt.internal.compiler.ast.TryStatement;
import org.mangosdk.spi.ProviderFor;

/**
 * Handles the {@code lombok.Cleanup} annotation for eclipse.
 */
@ProviderFor(EclipseAnnotationHandler.class)
public class HandleCleanup implements EclipseAnnotationHandler<Cleanup> {
	public boolean handle(AnnotationValues<Cleanup> annotation, Annotation ast, EclipseNode annotationNode) {
		String cleanupName = annotation.getInstance().value();
		if (cleanupName.length() == 0) {
			annotationNode.addError("cleanupName cannot be the empty string.");
			return true;
		}
		
		if (annotationNode.up().getKind() != Kind.LOCAL) {
			annotationNode.addError("@Cleanup is legal only on local variable declarations.");
			return true;
		}
		
		LocalDeclaration decl = (LocalDeclaration)annotationNode.up().get();
		
		if (decl.initialization == null) {
			annotationNode.addError("@Cleanup variable declarations need to be initialized.");
			return true;
		}
		
		EclipseNode ancestor = annotationNode.up().directUp();
		ASTNode blockNode = ancestor.get();
		
		final boolean isSwitch;
		final Statement[] statements;
		if (blockNode instanceof AbstractMethodDeclaration) {
			isSwitch = false;
			statements = ((AbstractMethodDeclaration)blockNode).statements;
		} else if (blockNode instanceof Block) {
			isSwitch = false;
			statements = ((Block)blockNode).statements;
		} else if (blockNode instanceof SwitchStatement) {
			isSwitch = true;
			statements = ((SwitchStatement)blockNode).statements;
		} else {
			annotationNode.addError("@Cleanup is legal only on a local variable declaration inside a block.");
			return true;
		}
		
		if (statements == null) {
			annotationNode.addError("LOMBOK BUG: Parent block does not contain any statements.");
			return true;
		}
		
		int start = 0;
		for (; start < statements.length ; start++) {
			if (statements[start] == decl) break;
		}
		
		if (start == statements.length) {
			annotationNode.addError("LOMBOK BUG: Can't find this local variable declaration inside its parent.");
			return true;
		}
		
		start++;  //We start with try{} *AFTER* the var declaration.
		
		int end;
		if (isSwitch) {
			end = start + 1;
			for (; end < statements.length ; end++) {
				if (statements[end] instanceof CaseStatement) {
					break;
				}
			}
		} else end = statements.length;
		
		//At this point:
		//  start-1 = Local Declaration marked with @Cleanup
		//  start = first instruction that needs to be wrapped into a try block
		//  end = last intruction of the scope -OR- last instruction before the next case label in switch statements.
		//  hence:
		//  [start, end) = statements for the try block.
		
		Statement[] tryBlock = new Statement[end - start];
		System.arraycopy(statements, start, tryBlock, 0, end-start);
		//Remove the stuff we just dumped into the tryBlock, and then leave room for the try node.
		int newStatementsLength = statements.length - (end-start); //Remove room for every statement moved into try block...
		newStatementsLength += 1; //But add room for the TryStatement node itself.
		Statement[] newStatements = new Statement[newStatementsLength];
		System.arraycopy(statements, 0, newStatements, 0, start); //copy all statements before the try block verbatim.
		System.arraycopy(statements, end, newStatements, start+1, statements.length - end); //For switch statements.
		
		doAssignmentCheck(annotationNode, tryBlock, decl.name);
		
		TryStatement tryStatement = new TryStatement();
		Eclipse.setGeneratedBy(tryStatement, ast);
		tryStatement.tryBlock = new Block(0);
		tryStatement.tryBlock.statements = tryBlock;
		newStatements[start] = tryStatement;
		
		Statement[] finallyBlock = new Statement[1];
		MessageSend unsafeClose = new MessageSend();
		Eclipse.setGeneratedBy(unsafeClose, ast);
		unsafeClose.sourceStart = ast.sourceStart;
		unsafeClose.sourceEnd = ast.sourceEnd;
		SingleNameReference receiver = new SingleNameReference(decl.name, 0);
		Eclipse.setGeneratedBy(receiver, ast);
		unsafeClose.receiver = receiver;
		long nameSourcePosition = (long)ast.sourceStart << 32 | ast.sourceEnd;
		if (ast.memberValuePairs() != null) for (MemberValuePair pair : ast.memberValuePairs()) {
			if (pair.name != null && new String(pair.name).equals("value")) {
				nameSourcePosition = (long)pair.value.sourceStart << 32 | pair.value.sourceEnd;
				break;
			}
		}
		unsafeClose.nameSourcePosition = nameSourcePosition;
		unsafeClose.selector = cleanupName.toCharArray();
		
		
		int pS = ast.sourceStart, pE = ast.sourceEnd;
		long p = (long)pS << 32 | pE;

		SingleNameReference varName = new SingleNameReference(decl.name, p);
		Eclipse.setGeneratedBy(varName, ast);
		NullLiteral nullLiteral = new NullLiteral(pS, pE);
		Eclipse.setGeneratedBy(nullLiteral, ast);
		
		MessageSend preventNullAnalysis = preventNullAnalysis(ast, varName);
		
		EqualExpression equalExpression = new EqualExpression(preventNullAnalysis, nullLiteral, OperatorIds.NOT_EQUAL);
		equalExpression.sourceStart = pS; equalExpression.sourceEnd = pE;
		Eclipse.setGeneratedBy(equalExpression, ast);
		
		Block closeBlock = new Block(0);
		closeBlock.statements = new Statement[1];
		closeBlock.statements[0] = unsafeClose;
		Eclipse.setGeneratedBy(closeBlock, ast);
		IfStatement ifStatement = new IfStatement(equalExpression, closeBlock, 0, 0);
		Eclipse.setGeneratedBy(ifStatement, ast);
		
		finallyBlock[0] = ifStatement;
		tryStatement.finallyBlock = new Block(0);
		Eclipse.setGeneratedBy(tryStatement.finallyBlock, ast);
		tryStatement.finallyBlock.statements = finallyBlock;
		
		tryStatement.catchArguments = null;
		tryStatement.catchBlocks = null;
		
		if (blockNode instanceof AbstractMethodDeclaration) {
			((AbstractMethodDeclaration)blockNode).statements = newStatements;
		} else if (blockNode instanceof Block) {
			((Block)blockNode).statements = newStatements;
		} else if (blockNode instanceof SwitchStatement) {
			((SwitchStatement)blockNode).statements = newStatements;
		}
		
		ancestor.rebuild();
		
		return true;
	}
	
	private MessageSend preventNullAnalysis(Annotation ast, Expression expr) {
		MessageSend singletonList = new MessageSend();
		Eclipse.setGeneratedBy(singletonList, ast);
		
		int pS = ast.sourceStart, pE = ast.sourceEnd;
		long p = (long)pS << 32 | pE;
		
		singletonList.receiver = createNameReference("java.util.Collections", ast);
		singletonList.selector = "singletonList".toCharArray();
		
		singletonList.arguments = new Expression[] { expr };
		singletonList.nameSourcePosition = p;
		singletonList.sourceStart = pS;
		singletonList.sourceEnd = singletonList.statementEnd = pE;
		
		MessageSend preventNullAnalysis = new MessageSend();
		Eclipse.setGeneratedBy(preventNullAnalysis, ast);
		
		preventNullAnalysis.receiver = singletonList;
		preventNullAnalysis.selector = "get".toCharArray();
		
		preventNullAnalysis.arguments = new Expression[] { new IntLiteral(new char[] { '0' }, pS, pE) };
		preventNullAnalysis.nameSourcePosition = p;
		preventNullAnalysis.sourceStart = pS;
		preventNullAnalysis.sourceEnd = singletonList.statementEnd = pE;
		
		return preventNullAnalysis;
	}
	
	private void doAssignmentCheck(EclipseNode node, Statement[] tryBlock, char[] varName) {
		for (Statement statement : tryBlock) doAssignmentCheck0(node, statement, varName);
	}
	
	private void doAssignmentCheck0(EclipseNode node, Statement statement, char[] varName) {
		if (statement instanceof Assignment)
			doAssignmentCheck0(node, ((Assignment)statement).expression, varName);
		else if (statement instanceof LocalDeclaration)
			doAssignmentCheck0(node, ((LocalDeclaration)statement).initialization, varName);
		else if (statement instanceof CastExpression)
			doAssignmentCheck0(node, ((CastExpression)statement).expression, varName);
		else if (statement instanceof SingleNameReference) {
			if (Arrays.equals(((SingleNameReference)statement).token, varName)) {
				EclipseNode problemNode = node.getNodeFor(statement);
				if (problemNode != null) problemNode.addWarning(
						"You're assigning an auto-cleanup variable to something else. This is a bad idea.");
			}
		}
	}
}
