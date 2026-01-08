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
package lombok.javac.handlers;

import static lombok.core.handlers.HandlerUtil.handleFlagUsage;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.Collection;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;

import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.DtoSetter;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.experimental.Accessors;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.spi.Provides;

/**
 * Handles the {@code lombok.DtoSetter} annotation for javac.
 */
@Provides
public class HandleDtoSetter extends JavacAnnotationHandler<DtoSetter> {
	private static final String DTO_SETTER_NODE_NOT_SUPPORTED_ERR = "@DtoSetter is only supported on a class or a field.";
	
	public void generateDtoSetterForType(JavacNode typeNode, JavacNode errorNode, AccessLevel level, boolean checkForTypeLevelSetter, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		if (checkForTypeLevelSetter) {
			if (hasAnnotation(DtoSetter.class, typeNode)) {
				//The annotation will make it happen, so we can skip it.
				return;
			}
		}
		
		if (!isClass(typeNode)) {
			errorNode.addError(DTO_SETTER_NODE_NOT_SUPPORTED_ERR);
			return;
		}
		
		for (JavacNode field : typeNode.down()) {
			if (field.getKind() != Kind.FIELD) continue;
			JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
			//Skip fields that start with $
			if (fieldDecl.name.toString().startsWith("$")) continue;
			//Skip static fields.
			if ((fieldDecl.mods.flags & Flags.STATIC) != 0) continue;
			//Skip final fields.
			if ((fieldDecl.mods.flags & Flags.FINAL) != 0) continue;
			
			generateDtoSetterForField(field, errorNode, level, onMethod, onParam);
		}
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
	 * 
	 * @param fieldNode The node representing the field you want a setter for.
	 * @param pos The node responsible for generating the setter (the {@code @Data} or {@code @DtoSetter} annotation).
	 */
	public void generateDtoSetterForField(JavacNode fieldNode, JavacNode sourceNode, AccessLevel level, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		if (hasAnnotation(DtoSetter.class, fieldNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}
		
		createDtoSetterForField(level, fieldNode, sourceNode, false, onMethod, onParam);
	}
	
	@Override public void handle(AnnotationValues<DtoSetter> annotation, JCAnnotation ast, JavacNode annotationNode) {
		handleFlagUsage(annotationNode, ConfigurationKeys.SETTER_FLAG_USAGE, "@DtoSetter");
		
		Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
		deleteAnnotationIfNeccessary(annotationNode, DtoSetter.class);
		deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
		JavacNode node = annotationNode.up();
		AccessLevel level = annotation.getInstance().value();
		
		if (level == AccessLevel.NONE || node == null) return;
		
		List<JCAnnotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@DtoSetter(onMethod", annotationNode);
		if (!onMethod.isEmpty()) {
			handleFlagUsage(annotationNode, ConfigurationKeys.ON_X_FLAG_USAGE, "@DtoSetter(onMethod=...)");
		}
		List<JCAnnotation> onParam = unboxAndRemoveAnnotationParameter(ast, "onParam", "@DtoSetter(onParam", annotationNode);
		if (!onParam.isEmpty()) {
			handleFlagUsage(annotationNode, ConfigurationKeys.ON_X_FLAG_USAGE, "@DtoSetter(onParam=...)");
		}
		
		switch (node.getKind()) {
		case FIELD:
			createDtoSetterForFields(level, fields, annotationNode, true, onMethod, onParam);
			break;
		case TYPE:
			generateDtoSetterForType(node, annotationNode, level, false, onMethod, onParam);
			break;
		}
	}
	
	public void createDtoSetterForFields(AccessLevel level, Collection<JavacNode> fieldNodes, JavacNode errorNode, boolean whineIfExists, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		for (JavacNode fieldNode : fieldNodes) {
			createDtoSetterForField(level, fieldNode, errorNode, whineIfExists, onMethod, onParam);
		}
	}
	
	public void createDtoSetterForField(AccessLevel level, JavacNode fieldNode, JavacNode sourceNode, boolean whineIfExists, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		if (fieldNode.getKind() != Kind.FIELD) {
			fieldNode.addError(DTO_SETTER_NODE_NOT_SUPPORTED_ERR);
			return;
		}
		
		AnnotationValues<Accessors> accessors = JavacHandlerUtil.getAccessorsForField(fieldNode);
		JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();
		String methodName = toSetterName(fieldNode, accessors);
		
		if (methodName == null) {
			fieldNode.addWarning("Not generating setter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		
		if ((fieldDecl.mods.flags & Flags.FINAL) != 0) {
			fieldNode.addWarning("Not generating setter for this field: Setters cannot be generated for final fields.");
			return;
		}
		
		for (String altName : toAllSetterNames(fieldNode, accessors)) {
			switch (methodExists(altName, fieldNode, false, 1)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					String altNameExpl = "";
					if (!altName.equals(methodName)) altNameExpl = String.format(" (%s)", altName);
					fieldNode.addWarning(
						String.format("Not generating %s(): A method with that name already exists%s", methodName, altNameExpl));
				}
				return;
			default:
			case NOT_EXISTS:
				//continue scanning the other alt names.
			}
		}
		
		long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);
		
		JCMethodDecl createdSetter = HandleSetter.createSetter(access, fieldNode, fieldNode.getTreeMaker(), sourceNode, onMethod, onParam);
		// Append changedMap().put("fieldName", param) to the end of the setter body
		if (createdSetter != null && createdSetter.params != null && !createdSetter.params.isEmpty()) {
			JavacTreeMaker maker = fieldNode.getTreeMaker();
			Name paramName = createdSetter.params.head.name;
			// call changedMap(): maker.Apply(..., chainDots(fieldNode, "this", "changedMap"), List.nil())
			JCExpression changedMapCall = maker.Apply(List.<JCExpression>nil(), chainDots(fieldNode, "this", "changedMap"), List.<JCExpression>nil());
			// select put on the result: maker.Select(changedMapCall, "put")
			JCExpression putSelect = maker.Select(changedMapCall, fieldNode.toName("put"));
			// arguments: literal field name and param ident
			JCExpression key = maker.Literal(fieldDecl.name.toString());
			JCExpression val = maker.Ident(paramName);
			JCStatement putCall = maker.Exec(maker.Apply(List.<JCExpression>nil(), putSelect, List.of(key, val)));
			if (createdSetter.body != null) {
				createdSetter.body.stats = createdSetter.body.stats.append(putCall);
			}
		}
		injectMethod(fieldNode.up(), createdSetter);
	}
}
