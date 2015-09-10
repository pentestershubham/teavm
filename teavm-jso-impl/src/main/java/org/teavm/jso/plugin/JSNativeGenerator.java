/*
 *  Copyright 2014 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.jso.plugin;

import java.io.IOException;
import org.teavm.codegen.SourceWriter;
import org.teavm.dependency.DependencyAgent;
import org.teavm.dependency.DependencyPlugin;
import org.teavm.dependency.MethodDependency;
import org.teavm.javascript.Renderer;
import org.teavm.javascript.ast.ConstantExpr;
import org.teavm.javascript.ast.Expr;
import org.teavm.javascript.ast.InvocationExpr;
import org.teavm.javascript.spi.Generator;
import org.teavm.javascript.spi.GeneratorContext;
import org.teavm.javascript.spi.Injector;
import org.teavm.javascript.spi.InjectorContext;
import org.teavm.model.CallLocation;
import org.teavm.model.ClassReader;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;

/**
 *
 * @author Alexey Andreev
 */
public class JSNativeGenerator implements Injector, DependencyPlugin, Generator {
    @Override
    public void generate(GeneratorContext context, SourceWriter writer, MethodReference methodRef)
            throws IOException {
        switch (methodRef.getName()) {
            case "function":
                writeFunction(context, writer);
                break;
        }
    }

    private void writeFunction(GeneratorContext context, SourceWriter writer) throws IOException {
        String thisName = context.getParameterName(1);
        String methodName = context.getParameterName(2);
        writer.append("var name").ws().append('=').ws().append("'jso$functor$'").ws().append('+').ws()
                .append(methodName).append(';').softNewLine();
        writer.append("if").ws().append("(!").append(thisName).append("[name])").ws().append('{')
                .indent().softNewLine();

        writer.append("var fn").ws().append('=').ws().append("function()").ws().append('{')
                .indent().softNewLine();
        writer.append("return ").append(thisName).append('[').append(methodName).append(']')
                .append(".apply(").append(thisName).append(',').ws().append("arguments);").softNewLine();
        writer.outdent().append("};").softNewLine();
        writer.append(thisName).append("[name]").ws().append('=').ws().append("function()").ws().append('{')
                .indent().softNewLine();
        writer.append("return fn;").softNewLine();
        writer.outdent().append("};").softNewLine();

        writer.outdent().append('}').softNewLine();
        writer.append("return ").append(thisName).append("[name]();").softNewLine();
    }

    @Override
    public void generate(InjectorContext context, MethodReference methodRef) throws IOException {
        SourceWriter writer = context.getWriter();
        switch (methodRef.getName()) {
            case "get":
                context.writeExpr(context.getArgument(0));
                renderProperty(context.getArgument(1), context);
                break;
            case "set":
                writer.append('(');
                context.writeExpr(context.getArgument(0));
                renderProperty(context.getArgument(1), context);
                writer.ws().append('=').ws();
                context.writeExpr(context.getArgument(2));
                writer.append(')');
                break;
            case "invoke":
                context.writeExpr(context.getArgument(0));
                renderProperty(context.getArgument(1), context);
                writer.append('(');
                for (int i = 2; i < context.argumentCount(); ++i) {
                    if (i > 2) {
                        writer.append(',').ws();
                    }
                    context.writeExpr(context.getArgument(i));
                }
                writer.append(')');
                break;
            case "instantiate":
                writer.append("(new (");
                context.writeExpr(context.getArgument(0));
                renderProperty(context.getArgument(1), context);
                writer.append(")(");
                for (int i = 2; i < context.argumentCount(); ++i) {
                    if (i > 2) {
                        writer.append(',').ws();
                    }
                    context.writeExpr(context.getArgument(i));
                }
                writer.append("))");
                break;
            case "wrap":
                if (methodRef.getDescriptor().parameterType(0).isObject("java.lang.String")) {
                    if (context.getArgument(0) instanceof ConstantExpr) {
                        ConstantExpr constant = (ConstantExpr) context.getArgument(0);
                        if (constant.getValue() instanceof String) {
                            writer.append('"').append(Renderer.escapeString((String) constant.getValue())).append('"');
                            break;
                        }
                    }
                    writer.append("$rt_ustr(");
                    context.writeExpr(context.getArgument(0));
                    writer.append(")");
                } else if (methodRef.getDescriptor().parameterType(0) == ValueType.BOOLEAN) {
                    writer.append("(!!(");
                    context.writeExpr(context.getArgument(0));
                    writer.append("))");
                } else {
                    context.writeExpr(context.getArgument(0));
                }
                break;
            case "function":
                generateFunction(context);
                break;
            case "unwrapString":
                writer.append("$rt_str(");
                context.writeExpr(context.getArgument(0));
                writer.append(")");
                break;
            case "unwrapBoolean":
                writer.append("(");
                context.writeExpr(context.getArgument(0));
                writer.ws().append("?").ws().append("1").ws().append(":").ws().append("0").append(")");
                break;
            default:
                if (methodRef.getName().startsWith("unwrap")) {
                    context.writeExpr(context.getArgument(0));
                }
                break;
        }
    }

    @Override
    public void methodAchieved(final DependencyAgent agent, final MethodDependency method,
            final CallLocation location) {
        switch (method.getReference().getName()) {
            case "invoke":
            case "instantiate":
            case "function":
                for (int i = 0; i < method.getReference().parameterCount(); ++i) {
                    method.getVariable(i).addConsumer(type -> achieveFunctorMethods(agent, type.getName(), method));
                }
                break;
            case "unwrapString":
                method.getResult().propagate(agent.getType("java.lang.String"));
                break;
        }
    }

    private void achieveFunctorMethods(DependencyAgent agent, String type, MethodDependency caller) {
        if (caller.isMissing()) {
            return;
        }
        ClassReader cls = agent.getClassSource().get(type);
        if (cls != null) {
            for (MethodReader method : cls.getMethods()) {
                agent.linkMethod(method.getReference(), null).use();
            }
        }
    }

    private void generateFunction(InjectorContext context) throws IOException {
        SourceWriter writer = context.getWriter();
        writer.append("(function($instance,").ws().append("$property)").ws().append("{").ws()
                .append("return function()").ws().append("{").indent().softNewLine();
        writer.append("return $instance[$property].apply($instance,").ws().append("arguments);").softNewLine();
        writer.outdent().append("};})(");
        context.writeExpr(context.getArgument(0));
        writer.append(",").ws();
        context.writeExpr(context.getArgument(1));
        writer.append(")");
    }

    private void renderProperty(Expr property, InjectorContext context) throws IOException {
        SourceWriter writer = context.getWriter();
        String name = extractPropertyName(property);
        if (name == null) {
            writer.append('[');
            context.writeExpr(property);
            writer.append(']');
        } else if (!isIdentifier(name)) {
            writer.append("[\"");
            context.writeEscaped(name);
            writer.append("\"]");
        } else {
            writer.append(".").append(name);
        }
    }

    private String extractPropertyName(Expr propertyName) {
        if (!(propertyName instanceof InvocationExpr)) {
            return null;
        }
        InvocationExpr invoke = (InvocationExpr) propertyName;
        if (!invoke.getMethod().getClassName().equals(JS.class.getName())) {
            return null;
        }
        if (!invoke.getMethod().getName().equals("wrap")
                || !invoke.getMethod().getDescriptor().parameterType(0).isObject("java.lang.String")) {
            return null;
        }
        Expr arg = invoke.getArguments().get(0);
        if (!(arg instanceof ConstantExpr)) {
            return null;
        }
        ConstantExpr constant = (ConstantExpr) arg;
        return constant.getValue() instanceof String ? (String) constant.getValue() : null;
    }

    private boolean isIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); ++i) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
