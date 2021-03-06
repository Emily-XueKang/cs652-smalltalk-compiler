package smalltalk.compiler;

import org.antlr.symtab.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import smalltalk.compiler.symbols.*;
import smalltalk.compiler.symbols.STCompiledBlock;

import java.util.List;

/** Fill STBlock, STMethod objects in Symbol table with bytecode,
 * {@link STCompiledBlock}.
 */
public class CodeGenerator extends SmalltalkBaseVisitor<Code> {
	public static final boolean dumpCode = false;

	public STClass currentClassScope;
	public Scope currentScope;

	/** With which compiler are we generating code? */
	public final Compiler compiler;

	public CodeGenerator(Compiler compiler) {
		this.compiler = compiler;
	}


	/** This and defaultResult() critical to getting code to bubble up the
	 *  visitor call stack when we don't implement every method.
	 */
	@Override
	protected Code aggregateResult(Code aggregate, Code nextResult) {
		if ( aggregate!=Code.None ) {
			if ( nextResult!=Code.None ) {
				return aggregate.join(nextResult);
			}
			return aggregate;
		}
		else {
			return nextResult;
		}
	}

	@Override
	protected Code defaultResult() {
		return Code.None;
	}

	@Override
	public Code visitFile(SmalltalkParser.FileContext ctx) {
		currentScope = compiler.symtab.GLOBALS;
		Code code = visitChildren(ctx);
		return code;
	}

	@Override
	public Code visitClassDef(SmalltalkParser.ClassDefContext ctx) {
		pushScope(ctx.scope);
		currentClassScope = ctx.scope;
		Code code = visitChildren(ctx);
		code = code.join(Compiler.pop());
		code = code.join(Compiler.push_self());
		code = code.join(Compiler.method_return());
		popScope();
		return code;
	}

	/**
	 All expressions have values. Must pop each expression value off, except
	 last one, which is the block return value. Visit method for blocks will
	 issue block_return instruction. Visit method for method will issue
	 pop self return.  If last expression is ^expr, the block_return or
	 pop self return is dead code but it is always there as a failsafe.

	 localVars? expr ('.' expr)* '.'?
	 */

	@Override
	public Code visitMain(SmalltalkParser.MainContext ctx) {
		currentClassScope = ctx.classScope;
		if (ctx.scope != null) {
			pushScope(ctx.scope);

            ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope,ctx.scope);
            STCompiledBlock[] blocks = new STCompiledBlock[ctx.scope.numNestedBlocks];
            int i = 0;
            for(Scope blocki: currentScope.getNestedScopedSymbols()){
                if(blocki instanceof STBlock){
                    blocks[i] = new STCompiledBlock(currentClassScope,(STBlock)blocki);
                    i+=1;
                }
            }
            ctx.scope.compiledBlock.blocks = blocks;
			Code code = visitChildren(ctx);
			code = code.join(Compiler.pop());
			code = code.join(Compiler.push_self());
			code = code.join(Compiler.method_return());
			ctx.scope.compiledBlock.bytecode = code.bytes();
			popScope();
			return code;
		}else {
			return Code.None;
		}
	}

	@Override
	public Code visitPrimitiveMethodBlock(SmalltalkParser.PrimitiveMethodBlockContext ctx) {
		SmalltalkParser.MethodContext methodContext = (SmalltalkParser.MethodContext) ctx.getParent();
		pushScope(methodContext.scope);
		Code code = visitChildren(ctx);
		STCompiledBlock compiledBlock = getCompiledPrimitive((STPrimitiveMethod) methodContext.scope);
		methodContext.scope.compiledBlock = compiledBlock;
		methodContext.scope.compiledBlock.bytecode = code.bytes();
		popScope();
		return code;
	}

	public STCompiledBlock getCompiledPrimitive(STPrimitiveMethod primitive) {
		STCompiledBlock compiledMethod = new STCompiledBlock(currentClassScope, primitive);
		return compiledMethod;
	}

	@Override
	public Code visitOperatorMethod(SmalltalkParser.OperatorMethodContext ctx) {
		pushScope(ctx.scope);
		Code code = visit(ctx.methodBlock());
		ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope,ctx.scope);
		ctx.scope.compiledBlock.bytecode = code.bytes();
		popScope();
		return code;
	}

	@Override
	public Code visitNamedMethod(SmalltalkParser.NamedMethodContext ctx) {
		pushScope(ctx.scope);
		Code code = visit(ctx.methodBlock());
		ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope,ctx.scope);
		ctx.scope.compiledBlock.bytecode = code.bytes();
		popScope();
		return code;
	}

	@Override
	public Code visitKeywordMethod(SmalltalkParser.KeywordMethodContext ctx) {
		pushScope(ctx.scope);
		Code code = visitChildren(ctx);
		ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope,ctx.scope);
		ctx.scope.compiledBlock.bytecode = code.bytes();
		popScope();
		return code;
	}

	@Override
	public Code visitSmalltalkMethodBlock(SmalltalkParser.SmalltalkMethodBlockContext ctx) {
		SmalltalkParser.MethodContext methodContext = (SmalltalkParser.MethodContext)ctx.getParent();

		if(methodContext.scope!=null){
			pushScope(methodContext.scope);
			methodContext.scope.compiledBlock = new STCompiledBlock(currentClassScope,methodContext.scope);
			methodContext.scope.compiledBlock.blocks = new STCompiledBlock[((SmalltalkParser.MethodContext)ctx.getParent()).scope.numNestedBlocks];
			STCompiledBlock[] stblocks = new STCompiledBlock[((SmalltalkParser.MethodContext)ctx.getParent()).scope.numNestedBlocks];
			Code code = visitChildren(ctx);
			if ( ctx.body() instanceof SmalltalkParser.FullBodyContext ) {
				code = code.join(Compiler.pop());
			}
			code = code.join(Compiler.push_self());
			code = code.join(Compiler.method_return());

			int i = 0;
			for(Scope blocki: currentScope.getNestedScopedSymbols()){
				if(blocki instanceof STBlock){
					stblocks[i] = ((STBlock) blocki).compiledBlock;
					stblocks[i].bytecode = ((STBlock) blocki).compiledBlock.bytecode;
					i+=1;
				}
			}
			methodContext.scope.compiledBlock.blocks = stblocks;

			popScope();
			return code;
		}
		else{
			return Code.None;
		}
	}


	@Override
	public Code visitBlock(SmalltalkParser.BlockContext ctx) {
		pushScope(ctx.scope);
		STBlock block = ctx.scope;

		Code code = Compiler.block(ctx.scope.index);

		Code bodycode = visitChildren(ctx);
		if(ctx.body().getChildCount() == 0){
			bodycode = bodycode.join(Compiler.push_nil());
		}
		bodycode = bodycode.join(Compiler.block_return());

		block.compiledBlock = new STCompiledBlock(currentClassScope,block);
		block.compiledBlock.bytecode = bodycode.bytes();

		Scope methodScope = currentScope.getEnclosingScope();
		while(!(methodScope instanceof STMethod)){
			methodScope = methodScope.getEnclosingScope();
		}
		((STBlock)methodScope).compiledBlock.blocks[block.index] = block.compiledBlock;

		popScope();
		return code;
	}

	@Override
	public Code visitFullBody(SmalltalkParser.FullBodyContext ctx) {
		Code code = new Code();
		if (ctx.localVars() != null) {
			code = code.join(visit(ctx.localVars()));
		}
		code = code.join(visit(ctx.stat(0)));
		if(ctx.stat().size() > 1) {
			for (int i = 1; i < (ctx.stat()).size(); i++) {
				code.join(Compiler.pop());
				code.join(visit(ctx.stat(i)));
			}
		}
		return code;
	}

	@Override
	public Code visitEmptyBody(SmalltalkParser.EmptyBodyContext ctx) {
		Code code = new Code();
		if (ctx.localVars() != null) {
			code = code.join(visit(ctx.localVars()));
		}
		return code;
	}


	@Override
	public Code visitAssign(SmalltalkParser.AssignContext ctx) {
		Code lvalue = store(ctx.lvalue().ID().getText());
		Code message = visit(ctx.messageExpression());
		Code code = message.join(lvalue);
		return code;
	}

	@Override
	public Code visitMessageExpression(SmalltalkParser.MessageExpressionContext ctx) {
		Code code = visit(ctx.keywordExpression());
		return code;
	}

	@Override
	public Code visitSendMessage(SmalltalkParser.SendMessageContext ctx) {
		Code code = visit(ctx.messageExpression());
		return code;
	}

	@Override
	public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
		Code code = visit(ctx.unaryExpression(0));
		if(ctx.bop() != null){
			for (int i = 0; i < ctx.bop().size(); i++){
				code.join(visit(ctx.unaryExpression(i + 1)));
				code.join(visit(ctx.bop(i)));
			}
		}
		return code;
	}


	@Override
	public Code visitBop(SmalltalkParser.BopContext ctx) {
		int literalIndex = getLiteralIndex(ctx.getText());
		Code code = Compiler.send(1, literalIndex);
		return code;
	}

	@Override
	public Code visitUnaryIsPrimary(SmalltalkParser.UnaryIsPrimaryContext ctx) {
		Code code = visitChildren(ctx);
		return code;
	}

	@Override
	public Code visitUnaryMsgSend(SmalltalkParser.UnaryMsgSendContext ctx) {
		Code code = visit(ctx.unaryExpression());
		int d = 0;
		code = code.join(Compiler.send(d, getLiteralIndex(ctx.ID().getText())));
		return code;
	}


	@Override
	public Code visitUnarySuperMsgSend(SmalltalkParser.UnarySuperMsgSendContext ctx) {
		Code code = new Code();
		code = code.join(Compiler.push_self());
		if(ctx.getText().contains("super")) {
			code = code.join(Compiler.send_super(0, (short) getLiteralIndex(ctx.ID().toString())));
		}
		return code;
	}

	@Override
	public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
		Code code = new Code();
		if(ctx.KEYWORD(0) != null) {
			code = code.join(visit(ctx.recv));
			String keyWord = "";
			for (int i = 0; i < ctx.args.size(); i++){
				code.join(visit(ctx.binaryExpression(i + 1)));
				keyWord = keyWord + ctx.KEYWORD(i).getText();
			}
			code.join(Compiler.send((keyWord.split(":").length), getLiteralIndex(keyWord)));
		} else {
			code = visitChildren(ctx);
		}
		return code;
	}

	@Override
	public Code visitPrimary(SmalltalkParser.PrimaryContext ctx) {
		Code code = new Code();
//		if(ctx.block() != null){
//			String[] split = ctx.block().scope.getName().split("-block");
//			String number = split[1];
//			//code = code.join(Compiler.block(Integer.valueOf(number)));
//		}
		code = code.join(visitChildren(ctx));
		return code;
	}

	@Override
	public Code visitId(SmalltalkParser.IdContext ctx) {
		Code code = push(ctx.getText());
		return code;
	}

	@Override
	public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
		Code code = new Code();
		int litIndex;
		if(ctx.NUMBER() != null){
			if(ctx.getText().contains(".")){
				code = Compiler.push_float(Float.parseFloat(ctx.getText()));
			}else {
				code = Compiler.push_int(Integer.parseInt(ctx.getText()));
			}
		}else if(ctx.CHAR() != null){
			litIndex = getLiteralIndex(ctx.getText());
			code = Compiler.push_char(litIndex);
		}else if(ctx.STRING() != null){
			litIndex = getLiteralIndex(ctx.getText());
			code = Compiler.push_literal(litIndex);
		}else if(ctx.getText().equals("nil")){
			code = Compiler.push_nil();
		}else if(ctx.getText().equals("self")){
			code = Compiler.push_self();
		}else if(ctx.getText().equals("true")) {
			code = Compiler.push_true();
		}else if(ctx.getText().equals("false")){
			code = Compiler.push_false();
		}
		return code;
	}


	@Override
	public Code visitPassThrough(SmalltalkParser.PassThroughContext ctx) {
		if(ctx.getChildCount() == 1){
			return visit(ctx.binaryExpression());
		}
		Code code = new Code();
		code = code.join(visit(ctx.recv));
		code.join(visit(ctx.binaryExpression()));
		return code;
	}

	@Override
	public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
		Code e = visit(ctx.messageExpression());
		Code code = e.join(Compiler.method_return());
		return code;
	}

	@Override
	public Code visitInstanceVars(SmalltalkParser.InstanceVarsContext ctx) {
		if(ctx != null) {
			Code code = new Code();
			for(TerminalNode localVarid: ctx.localVars().ID()) {
				code = push(localVarid.getText());
			}
			return code;
		}else {
			return Code.None;
		}
	}

	@Override
	public Code visitBlockArgs(SmalltalkParser.BlockArgsContext ctx) {
		Code code = new Code();
		for (int i = 0; i < ctx.ID().size(); i++){
			code = code.join(visit(ctx.ID(i)));
		}
		return code;
	}

	public int getLiteralIndex(String s)
	{
        if(s.contains("\'")){
            s = s.replace("\'", "");
        }
		return currentClassScope.stringTable.add(s);
	}


	public Code store(String id) {
		STBlock scope = (STBlock)currentScope;
		Symbol symbol = scope.resolve(id);
		if ( symbol==null ) return Code.None;
		if ( symbol.getScope() instanceof STBlock ) {
			STBlock methodScope = (STBlock)symbol.getScope();
			int delta = scope.getRelativeScopeCount(id);
			int litindex = methodScope.getLocalIndex(id);
			return Compiler.store_local(delta, litindex);
		}
		else if ( symbol.getScope() instanceof STClass ) {
			STClass field_in_Class = (STClass)symbol.getScope();
			int fieldIndex = field_in_Class.getFieldIndex(id);
			return Compiler.store_field(fieldIndex);
		}
		return Code.None;
	}

	public Code push(String id) {
		Scope scope = currentScope;
		Symbol var = scope.resolve(id);
		if(var != null) {
			if (var instanceof STField) {
				int i = 0;
				Object[] fields = currentClassScope.getFields().toArray();
				for(int j = 0; j < fields.length; j++){
					if (fields[j].toString().contains(id)){
						i = j;
					}
				}
				return Compiler.push_field(i);
			} else if ((var instanceof STVariable) || (var instanceof STArg)) {
				int i = var.getInsertionOrderNumber();
				int d = ((STBlock)scope).getRelativeScopeCount(id);
				return Compiler.push_local(d, i);
			} else if (var instanceof STClass){
				return Compiler.push_global(getLiteralIndex(var.getName()));
			}
		} else{
			return Compiler.push_global(getLiteralIndex(id));
		}
		return Code.None;
	}

	public void pushScope(Scope scope) {
		currentScope = scope;
	}

	public void popScope() {
//		if ( currentScope.getEnclosingScope()!=null ) {
//			System.out.println("popping from " + currentScope.getScopeName() + " to " + currentScope.getEnclosingScope().getScopeName());
//		}
//		else {
//			System.out.println("popping from " + currentScope.getScopeName() + " to null");
//		}
		currentScope = currentScope.getEnclosingScope();
	}
	public Code dbgAtEndMain(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		return dbg(t.getLine(), charPos);
	}

	public Code dbgAtEndBlock(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		charPos -= 1; // point at ']'
		return dbg(t.getLine(), charPos);
	}

	public Code dbg(Token t) {
		return dbg(t.getLine(), t.getCharPositionInLine());
	}

	public Code dbg(int line, int charPos) {
		return Compiler.dbg(getLiteralIndex(compiler.getFileName()), line, charPos);
	}




	public Code sendKeywordMsg(ParserRuleContext receiver,
							   Code receiverCode,
							   List<SmalltalkParser.BinaryExpressionContext> args,
							   List<TerminalNode> keywords)
	{
		return null;
	}

	public String getProgramSourceForSubtree(ParserRuleContext ctx)
	{
		return null;
	}
}
