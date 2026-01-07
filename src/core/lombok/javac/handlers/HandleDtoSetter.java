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

import static lombok.javac.Javac.*;
import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.Collection;

import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.DtoSetter;
import lombok.core.AST.Kind;
import lombok.experimental.Accessors;
import lombok.core.AnnotationValues;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.spi.Provides;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

/**
 * Handles the {@code lombok.DtoSetter} annotation for javac.
 */
@Provides
public class HandleDtoSetter extends JavacAnnotationHandler<DtoSetter> {
    private static final String DTOSETTER_NODE_NOT_SUPPORTED_ERR = "@DtoSetter is only supported on a class or a field.";

    public void generateSetterForType(JavacNode typeNode, JavacNode errorNode, AccessLevel level, boolean checkForTypeLevelSetter, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
        if (checkForTypeLevelSetter) {
            if (hasAnnotation(DtoSetter.class, typeNode)) {
                return;
            }
        }

        if (!isClass(typeNode)) {
            errorNode.addError(DTOSETTER_NODE_NOT_SUPPORTED_ERR);
            return;
        }

        for (JavacNode field : typeNode.down()) {
            if (field.getKind() != Kind.FIELD) continue;
            JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
            if (fieldDecl.name.toString().startsWith("$")) continue;
            if ((fieldDecl.mods.flags & Flags.STATIC) != 0) continue;
            if ((fieldDecl.mods.flags & Flags.FINAL) != 0) continue;

            generateSetterForField(field, errorNode, level, onMethod, onParam);
        }
    }

    public void generateSetterForField(JavacNode fieldNode, JavacNode sourceNode, AccessLevel level, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
        if (hasAnnotation(DtoSetter.class, fieldNode)) {
            return;
        }

        createSetterForField(level, fieldNode, sourceNode, false, onMethod, onParam);
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
            createSetterForFields(level, fields, annotationNode, true, onMethod, onParam);
            break;
        case TYPE:
            generateSetterForType(node, annotationNode, level, false, onMethod, onParam);
            break;
        }
    }

    public void createSetterForFields(AccessLevel level, Collection<JavacNode> fieldNodes, JavacNode errorNode, boolean whineIfExists, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
        for (JavacNode fieldNode : fieldNodes) {
            createSetterForField(level, fieldNode, errorNode, whineIfExists, onMethod, onParam);
        }
    }

    public void createSetterForField(AccessLevel level, JavacNode fieldNode, JavacNode sourceNode, boolean whineIfExists, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
        if (fieldNode.getKind() != Kind.FIELD) {
            fieldNode.addError(DTOSETTER_NODE_NOT_SUPPORTED_ERR);
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
                //continue
            }
        }

        long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);

        JCMethodDecl createdSetter = HandleSetter.createSetter(access, fieldNode, fieldNode.getTreeMaker(), sourceNode, onMethod, onParam);
        injectMethod(fieldNode.up(), createdSetter);
    }
}
