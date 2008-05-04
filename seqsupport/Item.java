package ajm.seqsupport;

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

import java.util.Arrays;

import ajm.maxsupport.Atomizer;
import ajm.rubysupport.MaxRubyEvaluator;

import com.cycling74.max.Atom;

/**
 * A single sequenced item: either be a single atom or a list of atoms (for example, a chord)
 * 
 * @version 0.85
 * @author Adam Murray (adam@compusition.com)
 */
public class Item implements Comparable<Item>, Atomizer {

	private Atom atom;
	private Atom[] atoms;

	private Atom rubyCodeAtom;
	private String rubyCode;
	private MaxRubyEvaluator ruby;

	private boolean reeval = true;

	private Item() {
	}

	public Item(Atom atom) {
		this.atom = atom;
	}

	public Item(int i) {
		this.atom = Atom.newAtom(i);
	}

	public Item(float f) {
		this.atom = Atom.newAtom(f);
	}

	public Item(String s) {
		this.atom = Atom.newAtom(s);
	}

	public Item(String rubyCode, MaxRubyEvaluator ruby) {
		// System.out.println("SET new ruby code: " + rubyCode + ruby);
		this.rubyCode = rubyCode;
		this.rubyCodeAtom = Atom.newAtom("{" + rubyCode + "}");
		this.ruby = ruby;
	}

	public Item(Atom[] atoms) {
		this.atoms = atoms;
		setAtomFromAtoms();
	}

	private void setAtomFromAtoms() {
		// Each item needs to be representable as a single Atom, so the underlying single Atom is
		// a symbol wrapped in quotes, like "60 64 67" for a C Major chord.
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < atoms.length; i++) {
			if (i > 0) {
				s.append(" ");
			}
			s.append(atoms[i].toString());
		}
		this.atom = Atom.newAtom(s.toString());
	}

	@Deprecated
	public boolean isAtomArray() {
		return atoms != null;
	}

	@Deprecated
	public Atom getAtom() {
		return atom;
	}

	/*
	@Deprecated
	public Atom[] getAtoms() {
		return atoms;
	}
	*/

	/**
	 * @return either Atom[] or Atom
	 */
	public Object getValue() {
		if (rubyCode != null && ruby != null) {
			// System.out.println("RUBY CODE");
			if (reeval) {
				// System.out.println("EVALUATING");
				Object value = ruby.eval(rubyCode);
				if (value instanceof Atom[]) {
					atoms = (Atom[]) value;
					setAtomFromAtoms();
				}
				else {
					atoms = null;
					atom = (Atom) value;
				}
				// TODO: reeval = false;
				// then need to explicitly set reeval true from the seq when appropriate
			}
		}

		if (atoms != null) {
			return atoms;
		}
		else {
			return atom;
		}
	}

	/**
	 * Convert the internal representation to an atom. If this is a dynamic Item (ruby code), then return the ruby code
	 * as a string, not the evaluated value.
	 */
	public Atom toAtom() {
		if (rubyCodeAtom != null) {
			return rubyCodeAtom;
		}
		else {
			return atom;
		}
	}

	// these kinds of operation must return a new item. otherwise we overwrite the defaultSeq values
	// I think cloning for the defaultSeq and resetseq will be too slow, as resets are fairly common
	// in my 16 track sequencer app
	public Item add(Atom summand) {
		if (isAtomArray()) {
			Atom[] sumAtoms = new Atom[atoms.length];
			for (int i = 0; i < atoms.length; i++) {
				Atom atom = atoms[i];
				if (atom.isFloat() || atom.isInt()) {
					sumAtoms[i] = add(atom, summand);
				}
				else {
					sumAtoms[i] = atom;
				}
			}
			return new Item(sumAtoms);

		}
		else {
			if (atom.isFloat() || atom.isInt()) {
				return new Item(add(atom, summand));
			}
			else {
				return this;
			}
		}
	}

	private static Atom add(Atom a1, Atom a2) {
		if (a1.isFloat() || a2.isFloat()) {
			return Atom.newAtom(a1.toFloat() + a2.toFloat());
		}
		else {
			return Atom.newAtom(a1.toInt() + a2.toInt());
		}
	}

	public Item multiply(Atom multiplier) {
		if (isAtomArray()) {
			Atom[] sumAtoms = new Atom[atoms.length];
			for (int i = 0; i < atoms.length; i++) {
				Atom atom = atoms[i];
				if (atom.isFloat() || atom.isInt()) {
					sumAtoms[i] = multiply(atom, multiplier);
				}
				else {
					sumAtoms[i] = atom;
				}
			}
			return new Item(sumAtoms);

		}
		else {
			if (atom.isFloat() || atom.isInt()) {
				return new Item(multiply(atom, multiplier));
			}
			else {
				return this;
			}
		}
	}

	private static Atom multiply(Atom a1, Atom a2) {
		if (a1.isFloat() || a2.isFloat()) {
			return Atom.newAtom(a1.toFloat() * a2.toFloat());
		}
		else {
			return Atom.newAtom(a1.toInt() * a2.toInt());
		}
	}

	public int compareTo(Item other) {
		Atom a1 = getAtom();
		Atom a2 = other.getAtom();
		if (a1.isString() || a2.isString()) {
			return a1.toString().compareTo(a2.toString());
		}
		else if (a1.isFloat() || a2.isFloat()) {
			// rely on Float.compare() because it handles infinity and NaN
			return Float.compare(a1.toFloat(), a2.toFloat());
		}
		else {
			return a1.toInt() - a2.toInt();
		}
	}

	public Item clone() {
		Item i = new Item();
		i.atom = atom;
		i.atoms = atoms;
		return i;
	}

	// TODO: this seems wrong...
	public boolean equals(Object obj) {
		if (obj instanceof Item) {
			return Arrays.equals(this.atoms, ((Item) obj).atoms);
		}
		else {
			return false;
		}
	}

	public String toString() {
		if (atoms != null) {
			return "\"" + atom + "\"";
		}
		else if (rubyCodeAtom != null) {
			return rubyCodeAtom.toString();
		}
		else {
			return atom + "";
		}
	}
}
