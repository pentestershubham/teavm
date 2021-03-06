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
package org.teavm.backend.c.generate;

import java.util.List;
import org.teavm.ast.ArrayType;
import org.teavm.ast.AssignmentStatement;
import org.teavm.ast.BinaryExpr;
import org.teavm.ast.BlockStatement;
import org.teavm.ast.BreakStatement;
import org.teavm.ast.CastExpr;
import org.teavm.ast.ConditionalExpr;
import org.teavm.ast.ConditionalStatement;
import org.teavm.ast.ConstantExpr;
import org.teavm.ast.ContinueStatement;
import org.teavm.ast.Expr;
import org.teavm.ast.ExprVisitor;
import org.teavm.ast.GotoPartStatement;
import org.teavm.ast.InitClassStatement;
import org.teavm.ast.InstanceOfExpr;
import org.teavm.ast.InvocationExpr;
import org.teavm.ast.MonitorEnterStatement;
import org.teavm.ast.MonitorExitStatement;
import org.teavm.ast.NewArrayExpr;
import org.teavm.ast.NewExpr;
import org.teavm.ast.NewMultiArrayExpr;
import org.teavm.ast.OperationType;
import org.teavm.ast.PrimitiveCastExpr;
import org.teavm.ast.QualificationExpr;
import org.teavm.ast.ReturnStatement;
import org.teavm.ast.SequentialStatement;
import org.teavm.ast.Statement;
import org.teavm.ast.StatementVisitor;
import org.teavm.ast.SubscriptExpr;
import org.teavm.ast.SwitchClause;
import org.teavm.ast.SwitchStatement;
import org.teavm.ast.ThrowStatement;
import org.teavm.ast.TryCatchStatement;
import org.teavm.ast.UnaryExpr;
import org.teavm.ast.UnwrapArrayExpr;
import org.teavm.ast.VariableExpr;
import org.teavm.ast.WhileStatement;
import org.teavm.backend.c.intrinsic.Intrinsic;
import org.teavm.backend.c.intrinsic.IntrinsicContext;
import org.teavm.diagnostics.Diagnostics;
import org.teavm.interop.Address;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.runtime.Allocator;
import org.teavm.runtime.ExceptionHandling;
import org.teavm.runtime.RuntimeClass;

public class CodeGenerationVisitor implements ExprVisitor, StatementVisitor {
    private static final MethodReference ALLOC_METHOD = new MethodReference(Allocator.class,
            "allocate", RuntimeClass.class, Address.class);
    private static final MethodReference ALLOC_ARRAY_METHOD = new MethodReference(Allocator.class,
            "allocateArray", RuntimeClass.class, int.class, Address.class);
    private static final MethodReference ALLOC_MULTI_ARRAY_METHOD = new MethodReference(Allocator.class,
            "allocateMultiArray", RuntimeClass.class, Address.class, int.class, Address.class);
    private static final MethodReference THROW_EXCEPTION_METHOD = new MethodReference(ExceptionHandling.class,
            "throwException", Throwable.class, void.class);

    private GenerationContext context;
    private NameProvider names;
    private CodeWriter writer;
    private int temporaryReceiverLevel;
    private int maxTemporaryReceiverLevel;
    private MethodReference callingMethod;

    public CodeGenerationVisitor(GenerationContext context, CodeWriter writer) {
        this.context = context;
        this.writer = writer;
        this.names = context.getNames();
    }

    public int getTemporaryReceivers() {
        return maxTemporaryReceiverLevel;
    }

    public void setCallingMethod(MethodReference callingMethod) {
        this.callingMethod = callingMethod;
    }

    @Override
    public void visit(BinaryExpr expr) {
        switch (expr.getOperation()) {
            case COMPARE:
                writer.print("compare_");
                switch (expr.getType()) {
                    case INT:
                        writer.print("i32");
                        break;
                    case LONG:
                        writer.print("i64");
                        break;
                    case FLOAT:
                        writer.print("float");
                        break;
                    case DOUBLE:
                        writer.print("double");
                        break;
                }
                writer.print("(");
                expr.getFirstOperand().acceptVisitor(this);
                writer.print(", ");
                expr.getSecondOperand().acceptVisitor(this);
                writer.print(")");
                return;
            case UNSIGNED_RIGHT_SHIFT: {
                String type = expr.getType() == OperationType.LONG ? "int64_t" : "int32_t";
                writer.print("((" + type + ") ((u" + type + ") ");

                expr.getFirstOperand().acceptVisitor(this);
                writer.print(" >> ");
                expr.getSecondOperand().acceptVisitor(this);

                writer.print("))");
                return;
            }

            default:
                break;
        }

        writer.print("(");
        expr.getFirstOperand().acceptVisitor(this);

        String op;
        switch (expr.getOperation()) {
            case ADD:
                op = "+";
                break;
            case SUBTRACT:
                op = "-";
                break;
            case MULTIPLY:
                op = "*";
                break;
            case DIVIDE:
                op = "/";
                break;
            case MODULO:
                op = "%";
                break;
            case BITWISE_AND:
                op = "&";
                break;
            case BITWISE_OR:
                op = "|";
                break;
            case BITWISE_XOR:
                op = "^";
                break;
            case LEFT_SHIFT:
                op = "<<";
                break;
            case RIGHT_SHIFT:
                op = ">>";
                break;
            case EQUALS:
                op = "==";
                break;
            case NOT_EQUALS:
                op = "!=";
                break;
            case GREATER:
                op = ">";
                break;
            case GREATER_OR_EQUALS:
                op = ">=";
                break;
            case LESS:
                op = "<";
                break;
            case LESS_OR_EQUALS:
                op = "<=";
                break;
            case AND:
                op = "&&";
                break;
            case OR:
                op = "||";
                break;
            default:
                throw new AssertionError();
        }

        writer.print(" ").print(op).print(" ");
        expr.getSecondOperand().acceptVisitor(this);
        writer.print(")");
    }

    @Override
    public void visit(UnaryExpr expr) {
        switch (expr.getOperation()) {
            case NOT:
                writer.print("(");
                writer.print("!");
                expr.getOperand().acceptVisitor(this);
                writer.print(")");
                break;
            case NEGATE:
                writer.print("(");
                writer.print("-");
                expr.getOperand().acceptVisitor(this);
                writer.print(")");
                break;
            case LENGTH:
                writer.print("ARRAY_LENGTH(");
                expr.getOperand().acceptVisitor(this);
                writer.print(")");
                break;
            case NULL_CHECK:
                expr.getOperand().acceptVisitor(this);
                break;
            case INT_TO_BYTE:
                writer.print("TO_BYTE(");
                expr.getOperand().acceptVisitor(this);
                writer.print(")");
                break;
            case INT_TO_SHORT:
                writer.print("TO_SHORT(");
                expr.getOperand().acceptVisitor(this);
                writer.print(")");
                break;
            case INT_TO_CHAR:
                writer.print("TO_CHAR(");
                expr.getOperand().acceptVisitor(this);
                writer.print(")");
                break;
        }
    }

    @Override
    public void visit(ConditionalExpr expr) {
        writer.print("(");
        expr.getCondition().acceptVisitor(this);
        writer.print(" ? ");
        expr.getConsequent().acceptVisitor(this);
        writer.print(" : ");
        expr.getAlternative().acceptVisitor(this);
        writer.print(")");
    }

    @Override
    public void visit(ConstantExpr expr) {
        Object value = expr.getValue();

        if (value == null) {
            writer.print("NULL");
        } else if (value instanceof String) {
            int index = context.getStringPool().getStringIndex((String) value);
            writer.print("(stringPool + " + index + ")");
        } else if (value instanceof Integer) {
            int i = (Integer) value;
            long v = i;
            if (i < 0) {
                writer.print("-");
                v = -i;
            }
            writer.print("INT32_C(");
            writeLongConstant(v);
            writer.print(")");
        } else if (value instanceof Long) {
            long v = (Long) value;
            if (v < 0) {
                writer.print("-");
                v = -v;
            }
            writer.print("INT64_C(");
            writeLongConstant(v);
            writer.print(")");
        } else if (value instanceof Float) {
            float f = (Float) value;
            if (Float.isInfinite(f)) {
                if (f < 0) {
                    writer.print("-");
                }
                writer.print("INFINITY");
            } else if (Float.isNaN(f)) {
                writer.print("NAN");
            } else {
                writer.print(f + "f");
            }
        } else if (value instanceof Double) {
            double d = (Double) value;
            if (Double.isInfinite(d)) {
                if (d < 0) {
                    writer.print("-");
                }
                writer.print("INFINITY");
            } else if (Double.isNaN(d)) {
                writer.print("NAN");
            } else {
                writer.print(value.toString());
            }
        } else if (value instanceof Boolean) {
            writer.print((Boolean) value ? "1" : "0");
        } else if (value instanceof ValueType) {
            writer.print("&").print(names.forClassInstance((ValueType) value));
        }
    }

    private void writeLongConstant(long v) {
        if (v == Long.MIN_VALUE) {
            writer.print("0x8000000000000000");
            return;
        }
        writer.print(String.valueOf(v));
    }

    @Override
    public void visit(VariableExpr expr) {
        if (expr.getIndex() == 0) {
            writer.print("_this_");
        } else {
            writer.print("local_" + expr.getIndex());
        }
    }

    @Override
    public void visit(SubscriptExpr expr) {
        writer.print("ARRAY_AT(");
        expr.getArray().acceptVisitor(this);
        writer.print(", ").print(getArrayType(expr.getType())).print(", ");
        expr.getIndex().acceptVisitor(this);
        writer.print(")");
    }

    @Override
    public void visit(UnwrapArrayExpr expr) {
        expr.getArray().acceptVisitor(this);
    }

    private static String getArrayType(ArrayType type) {
        switch (type) {
            case BYTE:
                return "int8_t";
            case SHORT:
                return "int16_t";
            case CHAR:
                return "char16_t";
            case INT:
                return "int32_t";
            case LONG:
                return "int64_t";
            case FLOAT:
                return "float";
            case DOUBLE:
                return "double";
            case OBJECT:
                return "void*";
            default:
                throw new AssertionError();
        }
    }

    @Override
    public void visit(InvocationExpr expr) {
        Intrinsic intrinsic = context.getIntrinsic(expr.getMethod());
        if (intrinsic != null) {
            intrinsic.apply(intrinsicContext, expr);
            return;
        }

        switch (expr.getType()) {
            case CONSTRUCTOR: {
                String receiver = "recv_" + temporaryReceiverLevel++;
                maxTemporaryReceiverLevel = Math.max(maxTemporaryReceiverLevel, temporaryReceiverLevel);
                writer.print("(" + receiver + " = ");
                allocObject(expr.getMethod().getClassName());
                writer.print(", ");

                MethodReader method = context.getClassSource().resolve(expr.getMethod());
                writer.print(names.forMethod(method.getReference()));

                writer.print("(" + receiver);
                for (Expr arg : expr.getArguments()) {
                    writer.print(", ");
                    arg.acceptVisitor(this);
                }
                writer.print("), " + receiver + ")");

                --temporaryReceiverLevel;

                break;
            }
            case SPECIAL:
            case STATIC: {
                MethodReader method = context.getClassSource().resolve(expr.getMethod());
                writer.print(names.forMethod(method.getReference()));

                writer.print("(");
                if (!expr.getArguments().isEmpty()) {
                    expr.getArguments().get(0).acceptVisitor(this);
                    for (int i = 1; i < expr.getArguments().size(); ++i) {
                        writer.print(", ");
                        expr.getArguments().get(i).acceptVisitor(this);
                    }
                }
                writer.print(")");

                break;
            }
            case DYNAMIC: {
                String receiver = "recv_" + temporaryReceiverLevel++;
                maxTemporaryReceiverLevel = Math.max(maxTemporaryReceiverLevel, temporaryReceiverLevel);
                writer.print("((").print(receiver).print(" = ");
                expr.getArguments().get(0).acceptVisitor(this);

                writer.print("), METHOD(")
                        .print(receiver).print(", ")
                        .print(names.forClassClass(expr.getMethod().getClassName())).print(", ")
                        .print(names.forVirtualMethod(expr.getMethod()))
                        .print(")(").print(receiver);
                for (int i = 1; i < expr.getArguments().size(); ++i) {
                    writer.print(", ");
                    expr.getArguments().get(i).acceptVisitor(this);
                }
                writer.print("))");

                temporaryReceiverLevel--;
                break;
            }
        }
    }

    @Override
    public void visit(QualificationExpr expr) {
        if (expr.getQualified() != null) {
            writer.print("FIELD(");
            expr.getQualified().acceptVisitor(this);
            writer.print(", ").print(names.forClass(expr.getField().getClassName()) + ", "
                    + names.forMemberField(expr.getField()) + ")");
        } else {
            writer.print(names.forStaticField(expr.getField()));
        }
    }

    @Override
    public void visit(NewExpr expr) {
        allocObject(expr.getConstructedClass());
    }

    private void allocObject(String className) {
        writer.print(names.forMethod(ALLOC_METHOD)).print("(&")
                .print(names.forClassInstance(ValueType.object(className)))
                .print(")");
    }

    @Override
    public void visit(NewArrayExpr expr) {
        writer.print(names.forMethod(ALLOC_ARRAY_METHOD)).print("(&")
                .print(names.forClassInstance(ValueType.arrayOf(expr.getType()))).print(", ");
        expr.getLength().acceptVisitor(this);
        writer.print(")");
    }

    @Override
    public void visit(NewMultiArrayExpr expr) {
        writer.print(names.forMethod(ALLOC_MULTI_ARRAY_METHOD)).print("(&")
                .print(names.forClassInstance(expr.getType())).print(", ");

        writer.print("(int32_t[]) {");
        expr.getDimensions().get(0).acceptVisitor(this);
        for (int i = 1; i < expr.getDimensions().size(); ++i) {
            writer.print(", ");
            expr.getDimensions().get(i).acceptVisitor(this);
        }

        writer.print("}, ").print(String.valueOf(expr.getDimensions().size())).print(")");
    }

    @Override
    public void visit(InstanceOfExpr expr) {
        writer.print("instanceof(");
        expr.getExpr().acceptVisitor(this);
        writer.print(", ").print(names.forSupertypeFunction(expr.getType())).print(")");
    }

    @Override
    public void visit(CastExpr expr) {
        if (expr.getTarget() instanceof ValueType.Object) {
            String className = ((ValueType.Object) expr.getTarget()).getClassName();
            if (context.getCharacteristics().isStructure(className)
                    || className.equals(Address.class.getName())) {
                expr.getValue().acceptVisitor(this);
                return;
            }
        }
        writer.print("checkcast(");
        expr.getValue().acceptVisitor(this);
        writer.print(", ").print(names.forSupertypeFunction(expr.getTarget())).print(")");
    }

    @Override
    public void visit(PrimitiveCastExpr expr) {
        writer.print("((");
        switch (expr.getTarget()) {
            case INT:
                writer.print("int32_t");
                break;
            case LONG:
                writer.print("int64_t");
                break;
            case FLOAT:
                writer.print("float");
                break;
            case DOUBLE:
                writer.print("double");
                break;
        }
        writer.print(") ");
        expr.getValue().acceptVisitor(this);
        writer.print(")");
    }

    @Override
    public void visit(AssignmentStatement statement) {
        if (statement.getLeftValue() != null) {
            statement.getLeftValue().acceptVisitor(this);
            writer.print(" = ");
        }
        statement.getRightValue().acceptVisitor(this);
        writer.println(";");
    }

    @Override
    public void visit(SequentialStatement statement) {
        visitMany(statement.getSequence());
    }

    private void visitMany(List<Statement> statements) {
        for (Statement statement : statements) {
            statement.acceptVisitor(this);
        }
    }

    @Override
    public void visit(ConditionalStatement statement) {
        while (true) {
            writer.print("if (");
            statement.getCondition().acceptVisitor(this);
            writer.println(") {").indent();

            visitMany(statement.getConsequent());
            writer.outdent().print("}");

            if (statement.getAlternative().isEmpty()) {
                writer.println();
                break;
            }

            writer.print(" else ");
            if (statement.getAlternative().size() == 1
                    && statement.getAlternative().get(0) instanceof ConditionalStatement) {
                statement = (ConditionalStatement) statement.getAlternative().get(0);
            } else {
                writer.println("{").indent();
                visitMany(statement.getAlternative());
                writer.outdent().println("}");
                break;
            }
        }
    }

    @Override
    public void visit(SwitchStatement statement) {
        writer.print("switch (");
        statement.getValue().acceptVisitor(this);
        writer.print(") {").println().indent();

        for (SwitchClause clause : statement.getClauses()) {
            for (int condition : clause.getConditions()) {
                writer.println("case " + condition + ":");
            }

            writer.indent();
            visitMany(clause.getBody());
            writer.println("break;");
            writer.outdent();
        }

        if (!statement.getDefaultClause().isEmpty()) {
            writer.println("default:").indent();
            visitMany(statement.getDefaultClause());
            writer.outdent();
        }

        writer.outdent().println("}");

        if (statement.getId() != null) {
            writer.outdent().println("label_" + statement.getId() + ":;").indent();
        }
    }

    @Override
    public void visit(WhileStatement statement) {
        writer.print("while (");
        if (statement.getCondition() != null) {
            statement.getCondition().acceptVisitor(this);
        } else {
            writer.print("1");
        }
        writer.println(") {").indent();

        visitMany(statement.getBody());

        if (statement.getId() != null) {
            writer.outdent().println("cnt_" + statement.getId() + ":;").indent();
        }
        writer.outdent().println("}");

        if (statement.getId() != null) {
            writer.outdent().println("label_" + statement.getId() + ":;").indent();
        }
    }

    @Override
    public void visit(BlockStatement statement) {
        visitMany(statement.getBody());

        if (statement.getId() != null) {
            writer.outdent().println("label_" + statement.getId() + ":;").indent();
        }
    }

    @Override
    public void visit(BreakStatement statement) {
        if (statement.getTarget() == null || statement.getTarget().getId() == null) {
            writer.println("break;");
        } else {
            writer.println("goto label_" + statement.getTarget().getId() + ";");
        }
    }

    @Override
    public void visit(ContinueStatement statement) {
        if (statement.getTarget() == null || statement.getTarget().getId() == null) {
            writer.println("continue;");
        } else {
            writer.println("goto cnt_" + statement.getTarget().getId() + ";");
        }
    }

    @Override
    public void visit(ReturnStatement statement) {
        writer.print("return");
        if (statement.getResult() != null) {
            writer.print(" ");
            statement.getResult().acceptVisitor(this);
        }
        writer.println(";");
    }

    @Override
    public void visit(ThrowStatement statement) {
        writer.print(names.forMethod(THROW_EXCEPTION_METHOD)).print("(");
        statement.getException().acceptVisitor(this);
        writer.println(");");
    }

    @Override
    public void visit(InitClassStatement statement) {
        writer.println(names.forClassInitializer(statement.getClassName()) + "();");
    }

    @Override
    public void visit(TryCatchStatement statement) {

    }

    @Override
    public void visit(GotoPartStatement statement) {
    }

    @Override
    public void visit(MonitorEnterStatement statement) {
    }

    @Override
    public void visit(MonitorExitStatement statement) {
    }

    private IntrinsicContext intrinsicContext = new IntrinsicContext() {
        @Override
        public CodeWriter writer() {
            return writer;
        }

        @Override
        public NameProvider names() {
            return names;
        }

        @Override
        public void emit(Expr expr) {
            expr.acceptVisitor(CodeGenerationVisitor.this);
        }

        @Override
        public Diagnostics getDiagnotics() {
            return context.getDiagnostics();
        }

        @Override
        public MethodReference getCallingMethod() {
            return callingMethod;
        }
    };
}
