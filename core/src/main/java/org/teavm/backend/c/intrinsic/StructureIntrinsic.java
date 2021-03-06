/*
 *  Copyright 2018 Alexey Andreev.
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
package org.teavm.backend.c.intrinsic;

import org.teavm.ast.ConstantExpr;
import org.teavm.ast.Expr;
import org.teavm.ast.InvocationExpr;
import org.teavm.interop.Structure;
import org.teavm.model.CallLocation;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.model.lowlevel.Characteristics;

public class StructureIntrinsic implements Intrinsic {
    private Characteristics characteristics;

    public StructureIntrinsic(Characteristics characteristics) {
        this.characteristics = characteristics;
    }

    @Override
    public boolean canHandle(MethodReference method) {
        if (!characteristics.isStructure(method.getClassName())) {
            return false;
        }

        switch (method.getName()) {
            case "cast":
            case "toAddress":
                return method.parameterCount() == 0;
            case "sizeOf":
                return method.parameterCount() == 1
                        && method.parameterType(0).equals(ValueType.parse(Class.class));
            case "add":
                return method.parameterCount() == 3
                        && method.parameterType(0).equals(ValueType.parse(Class.class))
                        && method.parameterType(1).equals(ValueType.parse(Structure.class))
                        && method.parameterType(2).equals(ValueType.INTEGER);
            default:
                return false;
        }
    }

    @Override
    public void apply(IntrinsicContext context, InvocationExpr invocation) {
        switch (invocation.getMethod().getName()) {
            case "cast":
            case "toAddress":
                context.emit(invocation.getArguments().get(0));
                break;
            case "sizeOf": {
                String className = getClassLiteral(context, invocation, invocation.getArguments().get(0));
                if (className != null) {
                    context.writer().print("sizeof(").print(context.names().forClass(className)).print(")");
                }
                break;
            }
            case "add": {
                String className = getClassLiteral(context, invocation, invocation.getArguments().get(0));
                if (className != null) {
                    context.writer().print("STRUCTURE_ADD(").print(context.names().forClass(className)).print(", ");
                    context.emit(invocation.getArguments().get(1));
                    context.writer().print(", ");
                    context.emit(invocation.getArguments().get(2));
                    context.writer().print(")");
                }
                break;
            }
        }
    }

    static String getClassLiteral(IntrinsicContext context, InvocationExpr invocation, Expr expr) {
        if (expr instanceof ConstantExpr) {
            Object cst = ((ConstantExpr) expr).getValue();
            if (cst instanceof ValueType.Object) {
                return ((ValueType.Object) cst).getClassName();
            }
        }
        context.getDiagnotics().error(
                new CallLocation(context.getCallingMethod(), invocation.getLocation()),
                "This method should take class literal");
        return "";
    }
}
