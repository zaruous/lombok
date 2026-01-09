/*
 * Copyright (C) 2009-2025 The Project Lombok Authors.
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

import static lombok.core.handlers.HandlerUtil.handleFlagUsage;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

import java.util.Collection;
import java.util.List;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;

import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.DtoSetter;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.experimental.Accessors;
import lombok.spi.Provides;

/**
 * Handles the {@code lombok.DtoSetter} annotation for eclipse.
 */
@Provides
public class HandleDtoSetter extends EclipseAnnotationHandler<DtoSetter> {
	private static final String DTO_SETTER_NODE_NOT_SUPPORTED_ERR = "@DtoSetter is only supported on a class or a field.";
	
	public boolean generateDtoSetterForType(EclipseNode typeNode, EclipseNode pos, AccessLevel level, boolean checkForTypeLevelSetter, List<Annotation> onMethod, List<Annotation> onParam) {
		if (checkForTypeLevelSetter) {
			if (hasAnnotation(DtoSetter.class, typeNode)) {
				//The annotation will make it happen, so we can skip it.
				return true;
			}
		}
		
		if (!isClass(typeNode)) {
			pos.addError(DTO_SETTER_NODE_NOT_SUPPORTED_ERR);
			return false;
		}
		
		for (EclipseNode field : typeNode.down()) {
			if (field.getKind() != Kind.FIELD) continue;
			FieldDeclaration fieldDecl = (FieldDeclaration) field.get();
			if (!filterField(fieldDecl)) continue;
			
			//Skip final fields.
			if ((fieldDecl.modifiers & ClassFileConstants.AccFinal) != 0) continue;
			
			generateDtoSetterForField(field, pos, level, onMethod, onParam);
		}
		return true;
	}
	
	/**
	 * Generates a dto setter on the stated field.
	 * 
	 * Used by {@link HandleData}.
	 * 
	 * The difference between this call and the handle method is as follows:
	 * 
	 * If there is a {@code lombok.DtoSetter} annotation on the field, it is used and the
	 * same rules apply (e.g. warning if the method already exists, stated access level applies).
	 * If not, the setter is still generated if it isn't already there, though there will not
	 * be a warning if its already there. The default access level is used.
	 */
	public void generateDtoSetterForField(EclipseNode fieldNode, EclipseNode sourceNode, AccessLevel level, List<Annotation> onMethod, List<Annotation> onParam) {
		if (hasAnnotation(DtoSetter.class, fieldNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}
		
		createDtoSetterForField(level, fieldNode, sourceNode, false, onMethod, onParam);
	}
	
	@Override public void handle(AnnotationValues<DtoSetter> annotation, Annotation ast, EclipseNode annotationNode) {
		handleFlagUsage(annotationNode, ConfigurationKeys.SETTER_FLAG_USAGE, "@DtoSetter");
		
		EclipseNode node = annotationNode.up();
		AccessLevel level = annotation.getInstance().value();
		if (level == AccessLevel.NONE || node == null) return;
		
		List<Annotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@DtoSetter(onMethod", annotationNode);
		if (!onMethod.isEmpty()) {
			handleFlagUsage(annotationNode, ConfigurationKeys.ON_X_FLAG_USAGE, "@DtoSetter(onMethod=...)");
		}
		List<Annotation> onParam = unboxAndRemoveAnnotationParameter(ast, "onParam", "@DtoSetter(onParam", annotationNode);
		if (!onParam.isEmpty()) {
			handleFlagUsage(annotationNode, ConfigurationKeys.ON_X_FLAG_USAGE, "@DtoSetter(onParam=...)");
		}
		
		switch (node.getKind()) {
		case FIELD:
			createDtoSetterForFields(level, annotationNode.upFromAnnotationToFields(), annotationNode, true, onMethod, onParam);
			break;
		case TYPE:
			generateDtoSetterForType(node, annotationNode, level, false, onMethod, onParam);
			break;
		}
	}
	
	public void createDtoSetterForFields(AccessLevel level, Collection<EclipseNode> fieldNodes, EclipseNode sourceNode, boolean whineIfExists, List<Annotation> onMethod, List<Annotation> onParam) {
		for (EclipseNode fieldNode : fieldNodes) {
			createDtoSetterForField(level, fieldNode, sourceNode, whineIfExists, onMethod, onParam);
		}
	}
	
	public void createDtoSetterForField(
			AccessLevel level, EclipseNode fieldNode, EclipseNode sourceNode,
			boolean whineIfExists, List<Annotation> onMethod,
			List<Annotation> onParam) {
		
		ASTNode source = sourceNode.get();
		if (fieldNode.getKind() != Kind.FIELD) {
			sourceNode.addError(DTO_SETTER_NODE_NOT_SUPPORTED_ERR);
			return;
		}
		
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		TypeReference fieldType = copyType(field.type, source);
		boolean isBoolean = isBoolean(fieldType);
		AnnotationValues<Accessors> accessors = getAccessorsForField(fieldNode);
		String setterName = toSetterName(fieldNode, isBoolean, accessors);
		boolean shouldReturnThis = shouldReturnThis(fieldNode, accessors);
		
		if (setterName == null) {
			fieldNode.addWarning("Not generating setter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		
		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);
		
		for (String altName : toAllSetterNames(fieldNode, isBoolean, accessors)) {
			switch (methodExists(altName, fieldNode, false, 1)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					String altNameExpl = "";
					if (!altName.equals(setterName)) altNameExpl = String.format(" (%s)", altName);
					fieldNode.addWarning(
						String.format("Not generating %s(): A method with that name already exists%s", setterName, altNameExpl));
				}
				return;
			default:
			case NOT_EXISTS:
				//continue scanning the other alt names.
			}
		}
		
		MethodDeclaration method = HandleSetter.createSetter((TypeDeclaration) fieldNode.up().get(), false, fieldNode, setterName, null, null, shouldReturnThis, modifier, sourceNode, onMethod, onParam);
		// Append changedMap().put("fieldName", param) 추가.
		if (method != null && method.arguments != null && method.arguments.length > 0) {
			int pS = source.sourceStart, pE = source.sourceEnd;
			long p = (long) pS << 32 | pE;

			MessageSend inner = new MessageSend();
			inner.sourceStart = pS; inner.sourceEnd = pE; inner.statementEnd = pE;
			inner.receiver = new ThisReference(pS, pE);
			inner.selector = "changedMap".toCharArray();
			inner.arguments = new Expression[0];

			MessageSend putCall = new MessageSend();
			putCall.sourceStart = pS; putCall.sourceEnd = pE; putCall.statementEnd = pE;
			putCall.receiver = inner;
			putCall.selector = "put".toCharArray();
			putCall.arguments = new Expression[] {
				new org.eclipse.jdt.internal.compiler.ast.StringLiteral(field.name, pS, pE, 0),
				new SingleNameReference(method.arguments[0].name, p)
			};

			Statement[] old = method.statements;
			Statement[] now = new Statement[(old == null ? 0 : old.length) + 1];
			if (old != null) System.arraycopy(old, 0, now, 0, old.length);
			now[now.length - 1] = putCall;
			method.statements = now;
		}
		injectMethod(fieldNode.up(), method);
	}
}
