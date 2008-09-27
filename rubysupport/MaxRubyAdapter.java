package ajm.rubysupport;

/*
Copyright (c) 2008, Adam Murray (adam@compusition.com). All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, 
this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

import java.io.File;

import org.jruby.RubyArray;
import org.jruby.RubyHash;
import org.jruby.RubySymbol;

import ajm.maxsupport.Atomizer;
import ajm.util.LineBuilder;
import ajm.util.Logger;
import ajm.util.Utils;

import com.cycling74.max.Atom;
import com.cycling74.max.MaxObject;
import com.cycling74.max.MaxSystem;

/**
 * The bridge between Max and Ruby.
 * 
 * @version 0.9
 * @author Adam Murray (adam@compusition.com)
 */
public class MaxRubyAdapter {

	public static final String NIL = "nil";

	private ScriptEvaluator ruby;

	private LineBuilder code = new LineBuilder();

	private LineBuilder scriptFileInit = new LineBuilder();

	private final MaxObject maxObject;

	private Logger logger;

	private String maxContext;

	private String id;

	private static String IGNORED_PATHS = RubyProperties.getIgnoredPaths();

	public MaxRubyAdapter(MaxObject maxObject, String context, String id) {
		this.maxObject = maxObject;
		if (maxObject instanceof Logger) {
			this.logger = (Logger) maxObject;
		}
		this.maxContext = context;
		this.id = id;
		getEvaluator();
	}

	private void getEvaluator() {
		ruby = ScriptEvaluatorManager.getRubyEvaluator(maxContext, id, maxObject);
		ruby.declareGlobal("max_ruby_adapter", this);
	}

	public Logger getLogger() {
		return logger;
	}

	public void setLogger(Logger logger) {
		this.logger = logger;
	}

	public String getContext() {
		return maxContext;
	}

	public void setContext(String context) {
		if (!Utils.equals(this.maxContext, context)) {
			// cleanup old context
			ScriptEvaluatorManager.removeRubyEvaluator(maxObject);

			// init new context
			this.maxContext = context;
			getEvaluator();
		}
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		if (!Utils.equals(this.id, id)) {
			ScriptEvaluatorManager.updateId(maxObject, id);
			this.id = id;
		}
	}

	public void notifyDeleted() {
		ScriptEvaluatorManager.removeRubyEvaluator(maxObject);
	}

	public void exec(CharSequence rubyCode) {
		eval(rubyCode, false);
	}

	public void eval(CharSequence rubyCode) {
		eval(rubyCode, true);
	}

	/**
	 * @return an Atom or Atom[], it's up to the calling code to check the type
	 */
	public Object eval(CharSequence rubyCode, boolean returnResult) {
		if (!ruby.isInitialized()) {
			init();
		}
		Object result;
		synchronized (ruby) {
			// Set the $MaxObject/ID globals correctly in shared contexts:
			ruby.declareGlobal("MaxObject", maxObject); // for backward compatibility
			ruby.declareGlobal("max_object", maxObject); // the new preferred ruby-ish variable name
			result = ruby.eval(rubyCode);
		}
		if (returnResult) {
			return toAtoms(result, true);
		}
		else {
			return null;
		}
	}

	public void init() {
		init(null, Atom.emptyArray);
	}

	public void init(File scriptFile, Atom[] args) {
		if (code.isEmpty()) {
			for (String path : MaxSystem.getSearchPath()) {
				if (!path.matches(IGNORED_PATHS)) {
					// Add the path to Ruby's search path:
					code.line("$: << " + quote(path));
				}
			}
			String initializationCode = Utils.getFileAsString("ajm_ruby_initialize.rb");
			code.append(initializationCode);
		}

		if (ruby.isInitialized()) {
			ScriptEvaluatorManager.notifyContextDestroyedListener(maxContext, maxObject);
			ruby.resetContext();
		}
		ruby.setInitialized(true);
		exec(code);

		if (scriptFile != null) {
			String script = Utils.getFileAsString(scriptFile);
			scriptFileInit.clear();
			scriptFileInit.line("$0 = " + quote(scriptFile));
			for (Atom arg : args) {
				scriptFileInit.line("$* << " + Utils.detokenize(arg));
			}
			exec(scriptFileInit);
			if (script != null) {
				exec(script);
			}
		}
	}

	private String quote(Object o) {
		if (o == null) return NIL;
		String s = o.toString();
		if (s == null) return NIL;
		return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
	}

	/**
	 * Converts the result of a Ruby evaluation into Max data types (Atoms)
	 * 
	 * @param obj -
	 *            A Ruby value
	 * @return an Atom or an Atom[]. The calling code needs to figure out what type this is and handle it appropriately
	 */
	public Object toAtoms(Object obj) {
		return toAtoms(obj, null);
	}

	public Object toAtoms(Object obj, boolean logCoercions) {
		return toAtoms(obj, (logCoercions ? logger : null));
	}

	private Object toAtoms(Object obj, Logger logger) {
		if (obj == null) {
			return Atom.newAtom("nil");
		}
		else if (obj instanceof Atom || obj instanceof Atom[]) {
			return obj;
		}
		else if (obj instanceof Atomizer) {
			return ((Atomizer) obj).toAtom();
		}
		else if (obj instanceof Double || obj instanceof Float) {
			// Not sure if there's a situation where we should coerce to a String,
			// because Max can only handle floats and JRuby always outputs Doubles.
			// Floating point accuracy is very different from the Long wrap-around problem,
			// so letting it downcast seems ok:
			return Atom.newAtom(((Number) obj).doubleValue());
		}
		else if (obj instanceof Long || obj instanceof Integer || obj instanceof Short) {
			long val = ((Number) obj).longValue();
			if (val > Integer.MAX_VALUE || val < Integer.MIN_VALUE) {
				if (logger != null) {
					logger.info("coerced type " + obj.getClass().getName() + " to String");
				}
				return Atom.newAtom(obj.toString());
			}
			else return Atom.newAtom(val);
		}

		else if (obj instanceof CharSequence || obj instanceof RubySymbol) {
			return Atom.newAtom(obj.toString());
		}

		else if (obj instanceof Boolean) {
			return Atom.newAtom(((Boolean) obj).booleanValue());
		}

		else if (obj instanceof RubyArray) {
			RubyArray array = (RubyArray) obj;

			Object[] atomsArray = new Object[array.size()];
			boolean isFlatArray = true;
			for (int i = 0; i < array.size(); i++) {
				Object val = toAtoms(array.get(i), logger);
				if (!(val instanceof Atom)) {
					isFlatArray = false;
				}
				atomsArray[i] = val;
			}

			if (isFlatArray) {
				Atom[] atoms = new Atom[array.size()];
				for (int i = 0; i < atomsArray.length; i++) {
					atoms[i] = (Atom) atomsArray[i];
				}
				return atoms;
			}
			else {
				if (logger != null) {
					logger.info("coerced a nested Array to String");
				}
				return Atom.newAtom(toArrayString(atomsArray));
			}
		}

		else if (obj instanceof RubyHash) {
			if (logger != null) {
				logger.info("coerced a Hash to String");
			}
			RubyHash hash = (RubyHash) obj;
			StringBuilder s = new StringBuilder();
			for (Object key : hash.keySet()) {
				if (s.length() > 0) {
					s.append(", ");
				}
				s.append(toArrayString(toAtoms(key)));
				s.append("=>");
				s.append(toArrayString(toAtoms(hash.get(key))));
			}
			s.insert(0, "{");
			s.append("}");
			return new Atom[] { Atom.newAtom(s.toString()) };
		}

		else {
			if (logger != null) {
				logger.info("coerced type " + obj.getClass().getName() + " to String");
			}
			return Atom.newAtom(obj.toString());
		}

	}

	private String toArrayString(Object o) {
		StringBuilder s = new StringBuilder();
		buildArrayString(o, s);
		return s.toString();
	}

	private void buildArrayString(Object o, StringBuilder s) {
		if (o instanceof Object[]) {
			s.append("[");
			Object[] objs = (Object[]) o;
			for (int i = 0; i < objs.length; i++) {
				if (i > 0) {
					s.append(",");
				}
				buildArrayString(objs[i], s);
			}
			s.append("]");
		}
		else {
			s.append(o.toString());
		}
	}

	public void on_context_destroyed(Object callback) {
		ScriptEvaluatorManager.registerContextDestroyedListener(maxObject, callback.toString());
	}
}
