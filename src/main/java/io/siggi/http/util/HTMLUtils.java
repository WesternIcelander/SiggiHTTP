package io.siggi.http.util;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * HTML Utilities
 */
public final class HTMLUtils {

	private HTMLUtils() {
	}

	// <editor-fold defaultstate="collapsed" desc="HTML/XML Escaping">
	/**
	 * Escapes characters in a string that may otherwise appear differently
	 * unescaped.
	 * <ul>
	 * <li>&amp; -&gt; &amp;amp;</li>
	 * <li>&quot; -&gt; &amp;quot;</li>
	 * <li>&lt; -&gt; &amp;lt;</li>
	 * <li>&gt; -&gt; &amp;gt;</li>
	 * </ul>
	 *
	 * @param text The text to escape
	 * @return The escaped text
	 * @deprecated Use {@link #htmlentities(java.lang.String)} or
	 * {@link #htmlentities(java.lang.String, boolean)} instead.
	 */
	@Deprecated
	public static String escape(String text) {
		return text.replaceAll("&", "&amp;").replaceAll("\"", "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
	}

	/**
	 * Escape text to show on an HTML page.
	 *
	 * @param s The text to escape
	 * @return The escaped text
	 */
	public static String htmlentities(String s) {
		return htmlentities(s, true);
	}

	/**
	 * Escape text to show on an HTML page, optionally deciding whether to
	 * escape multiple spaces to nbsp which you don't want if you're putting
	 * editable text inside an &lt;input value=&quot;&quot;&gt; or a
	 * &lt;textarea&gt;.
	 *
	 * @param s The text to escape
	 * @param escapeSpace The escaped text
	 * @return
	 */
	public static String htmlentities(String s, boolean escapeSpace) {
		return xentities(s, true, escapeSpace);
	}

	/**
	 * Escape text to include inside an XML document.
	 *
	 * @param s The text to escape
	 * @return The escaped text
	 */
	public static String xmlentities(String s) {
		return xentities(s, false, false);
	}

	private static String xentities(String s, boolean html, boolean escapeSpace) {
		StringBuilder sb = new StringBuilder();
		int n = s.length();
		char prevChar = (char) 0;
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			// <editor-fold defaultstate="collapsed" desc="A crapload of html entities">
			if (c == '"') {
				appendEntity(html, sb, c, "&quot;");
			} else if (c == '&') {
				appendEntity(html, sb, c, "&amp;");
			} else if (c == 39) {
				appendEntity(html, sb, c, "&apos;");
			} else if (c == '<') {
				appendEntity(html, sb, c, "&lt;");
			} else if (c == '>') {
				appendEntity(html, sb, c, "&gt;");
			} else if (c == ' ' && prevChar == ' ' && escapeSpace) {
				appendEntity(html, sb, c, "&nbsp;");
			} else if (c == '¡') {
				appendEntity(html, sb, c, "&iexcl;");
			} else if (c == '¢') {
				appendEntity(html, sb, c, "&cent;");
			} else if (c == '£') {
				appendEntity(html, sb, c, "&pound;");
			} else if (c == '¤') {
				appendEntity(html, sb, c, "&curren;");
			} else if (c == '¥') {
				appendEntity(html, sb, c, "&yen;");
			} else if (c == '¦') {
				appendEntity(html, sb, c, "&brvbar;");
			} else if (c == '§') {
				appendEntity(html, sb, c, "&sect;");
			} else if (c == '¨') {
				appendEntity(html, sb, c, "&uml;");
			} else if (c == '©') {
				appendEntity(html, sb, c, "&copy;");
			} else if (c == 'ª') {
				appendEntity(html, sb, c, "&ordf;");
			} else if (c == '«') {
				appendEntity(html, sb, c, "&laquo;");
			} else if (c == '¬') {
				appendEntity(html, sb, c, "&not;");
			} else if (c == '®') {
				appendEntity(html, sb, c, "&reg;");
			} else if (c == '¯') {
				appendEntity(html, sb, c, "&macr;");
			} else if (c == '°') {
				appendEntity(html, sb, c, "&deg;");
			} else if (c == '±') {
				appendEntity(html, sb, c, "&plusmn;");
			} else if (c == '²') {
				appendEntity(html, sb, c, "&sup2;");
			} else if (c == '³') {
				appendEntity(html, sb, c, "&sup3;");
			} else if (c == '´') {
				appendEntity(html, sb, c, "&acute;");
			} else if (c == 'µ') {
				appendEntity(html, sb, c, "&micro;");
			} else if (c == '¶') {
				appendEntity(html, sb, c, "&para;");
			} else if (c == '·') {
				appendEntity(html, sb, c, "&middot;");
			} else if (c == '¸') {
				appendEntity(html, sb, c, "&cedil;");
			} else if (c == '¹') {
				appendEntity(html, sb, c, "&sup1;");
			} else if (c == 'º') {
				appendEntity(html, sb, c, "&ordm;");
			} else if (c == '»') {
				appendEntity(html, sb, c, "&raquo;");
			} else if (c == '¼') {
				appendEntity(html, sb, c, "&frac14;");
			} else if (c == '½') {
				appendEntity(html, sb, c, "&frac12;");
			} else if (c == '¾') {
				appendEntity(html, sb, c, "&frac34;");
			} else if (c == '¿') {
				appendEntity(html, sb, c, "&iquest;");
			} else if (c == 'À') {
				appendEntity(html, sb, c, "&Agrave;");
			} else if (c == 'Á') {
				appendEntity(html, sb, c, "&Aacute;");
			} else if (c == 'Â') {
				appendEntity(html, sb, c, "&Acirc;");
			} else if (c == 'Ã') {
				appendEntity(html, sb, c, "&Atilde;");
			} else if (c == 'Ä') {
				appendEntity(html, sb, c, "&Auml;");
			} else if (c == 'Å') {
				appendEntity(html, sb, c, "&Aring;");
			} else if (c == 'Æ') {
				appendEntity(html, sb, c, "&AElig;");
			} else if (c == 'Ç') {
				appendEntity(html, sb, c, "&Ccedil;");
			} else if (c == 'È') {
				appendEntity(html, sb, c, "&Egrave;");
			} else if (c == 'É') {
				appendEntity(html, sb, c, "&Eacute;");
			} else if (c == 'Ê') {
				appendEntity(html, sb, c, "&Ecirc;");
			} else if (c == 'Ë') {
				appendEntity(html, sb, c, "&Euml;");
			} else if (c == 'Ì') {
				appendEntity(html, sb, c, "&Igrave;");
			} else if (c == 'Í') {
				appendEntity(html, sb, c, "&Iacute;");
			} else if (c == 'Î') {
				appendEntity(html, sb, c, "&Icirc;");
			} else if (c == 'Ï') {
				appendEntity(html, sb, c, "&Iuml;");
			} else if (c == 'Ð') {
				appendEntity(html, sb, c, "&ETH;");
			} else if (c == 'Ñ') {
				appendEntity(html, sb, c, "&Ntilde;");
			} else if (c == 'Ò') {
				appendEntity(html, sb, c, "&Ograve;");
			} else if (c == 'Ó') {
				appendEntity(html, sb, c, "&Oacute;");
			} else if (c == 'Ô') {
				appendEntity(html, sb, c, "&Ocirc;");
			} else if (c == 'Õ') {
				appendEntity(html, sb, c, "&Otilde;");
			} else if (c == 'Ö') {
				appendEntity(html, sb, c, "&Ouml;");
			} else if (c == '×') {
				appendEntity(html, sb, c, "&times;");
			} else if (c == 'Ø') {
				appendEntity(html, sb, c, "&Oslash;");
			} else if (c == 'Ù') {
				appendEntity(html, sb, c, "&Ugrave;");
			} else if (c == 'Ú') {
				appendEntity(html, sb, c, "&Uacute;");
			} else if (c == 'Û') {
				appendEntity(html, sb, c, "&Ucirc;");
			} else if (c == 'Ü') {
				appendEntity(html, sb, c, "&Uuml;");
			} else if (c == 'Ý') {
				appendEntity(html, sb, c, "&Yacute;");
			} else if (c == 'Þ') {
				appendEntity(html, sb, c, "&THORN;");
			} else if (c == 'ß') {
				appendEntity(html, sb, c, "&szlig;");
			} else if (c == 'à') {
				appendEntity(html, sb, c, "&agrave;");
			} else if (c == 'á') {
				appendEntity(html, sb, c, "&aacute;");
			} else if (c == 'â') {
				appendEntity(html, sb, c, "&acirc;");
			} else if (c == 'ã') {
				appendEntity(html, sb, c, "&atilde;");
			} else if (c == 'ä') {
				appendEntity(html, sb, c, "&auml;");
			} else if (c == 'å') {
				appendEntity(html, sb, c, "&aring;");
			} else if (c == 'æ') {
				appendEntity(html, sb, c, "&aelig;");
			} else if (c == 'ç') {
				appendEntity(html, sb, c, "&ccedil;");
			} else if (c == 'è') {
				appendEntity(html, sb, c, "&egrave;");
			} else if (c == 'é') {
				appendEntity(html, sb, c, "&eacute;");
			} else if (c == 'ê') {
				appendEntity(html, sb, c, "&ecirc;");
			} else if (c == 'ë') {
				appendEntity(html, sb, c, "&euml;");
			} else if (c == 'ì') {
				appendEntity(html, sb, c, "&igrave;");
			} else if (c == 'í') {
				appendEntity(html, sb, c, "&iacute;");
			} else if (c == 'î') {
				appendEntity(html, sb, c, "&icirc;");
			} else if (c == 'ï') {
				appendEntity(html, sb, c, "&iuml;");
			} else if (c == 'ð') {
				appendEntity(html, sb, c, "&eth;");
			} else if (c == 'ñ') {
				appendEntity(html, sb, c, "&ntilde;");
			} else if (c == 'ò') {
				appendEntity(html, sb, c, "&ograve;");
			} else if (c == 'ó') {
				appendEntity(html, sb, c, "&oacute;");
			} else if (c == 'ô') {
				appendEntity(html, sb, c, "&ocirc;");
			} else if (c == 'õ') {
				appendEntity(html, sb, c, "&otilde;");
			} else if (c == 'ö') {
				appendEntity(html, sb, c, "&ouml;");
			} else if (c == '÷') {
				appendEntity(html, sb, c, "&divide;");
			} else if (c == 'ø') {
				appendEntity(html, sb, c, "&oslash;");
			} else if (c == 'ù') {
				appendEntity(html, sb, c, "&ugrave;");
			} else if (c == 'ú') {
				appendEntity(html, sb, c, "&uacute;");
			} else if (c == 'û') {
				appendEntity(html, sb, c, "&ucirc;");
			} else if (c == 'ü') {
				appendEntity(html, sb, c, "&uuml;");
			} else if (c == 'ý') {
				appendEntity(html, sb, c, "&yacute;");
			} else if (c == 'þ') {
				appendEntity(html, sb, c, "&thorn;");
			} else if (c == 'ÿ') {
				appendEntity(html, sb, c, "&yuml;");
			} else if (c == 'Œ') {
				appendEntity(html, sb, c, "&OElig;");
			} else if (c == 'œ') {
				appendEntity(html, sb, c, "&oelig;");
			} else if (c == 'Š') {
				appendEntity(html, sb, c, "&Scaron;");
			} else if (c == 'š') {
				appendEntity(html, sb, c, "&scaron;");
			} else if (c == 'Ÿ') {
				appendEntity(html, sb, c, "&Yuml;");
			} else if (c == 'ƒ') {
				appendEntity(html, sb, c, "&fnof;");
			} else if (c == 'ˆ') {
				appendEntity(html, sb, c, "&circ;");
			} else if (c == '˜') {
				appendEntity(html, sb, c, "&tilde;");
			} else if (c == 'Α') {
				appendEntity(html, sb, c, "&Alpha;");
			} else if (c == 'Β') {
				appendEntity(html, sb, c, "&Beta;");
			} else if (c == 'Γ') {
				appendEntity(html, sb, c, "&Gamma;");
			} else if (c == 'Δ') {
				appendEntity(html, sb, c, "&Delta;");
			} else if (c == 'Ε') {
				appendEntity(html, sb, c, "&Epsilon;");
			} else if (c == 'Ζ') {
				appendEntity(html, sb, c, "&Zeta;");
			} else if (c == 'Η') {
				appendEntity(html, sb, c, "&Eta;");
			} else if (c == 'Θ') {
				appendEntity(html, sb, c, "&Theta;");
			} else if (c == 'Ι') {
				appendEntity(html, sb, c, "&Iota;");
			} else if (c == 'Κ') {
				appendEntity(html, sb, c, "&Kappa;");
			} else if (c == 'Λ') {
				appendEntity(html, sb, c, "&Lambda;");
			} else if (c == 'Μ') {
				appendEntity(html, sb, c, "&Mu;");
			} else if (c == 'Ν') {
				appendEntity(html, sb, c, "&Nu;");
			} else if (c == 'Ξ') {
				appendEntity(html, sb, c, "&Xi;");
			} else if (c == 'Ο') {
				appendEntity(html, sb, c, "&Omicron;");
			} else if (c == 'Π') {
				appendEntity(html, sb, c, "&Pi;");
			} else if (c == 'Ρ') {
				appendEntity(html, sb, c, "&Rho;");
			} else if (c == 'Σ') {
				appendEntity(html, sb, c, "&Sigma;");
			} else if (c == 'Τ') {
				appendEntity(html, sb, c, "&Tau;");
			} else if (c == 'Υ') {
				appendEntity(html, sb, c, "&Upsilon;");
			} else if (c == 'Φ') {
				appendEntity(html, sb, c, "&Phi;");
			} else if (c == 'Χ') {
				appendEntity(html, sb, c, "&Chi;");
			} else if (c == 'Ψ') {
				appendEntity(html, sb, c, "&Psi;");
			} else if (c == 'Ω') {
				appendEntity(html, sb, c, "&Omega;");
			} else if (c == 'α') {
				appendEntity(html, sb, c, "&alpha;");
			} else if (c == 'β') {
				appendEntity(html, sb, c, "&beta;");
			} else if (c == 'γ') {
				appendEntity(html, sb, c, "&gamma;");
			} else if (c == 'δ') {
				appendEntity(html, sb, c, "&delta;");
			} else if (c == 'ε') {
				appendEntity(html, sb, c, "&epsilon;");
			} else if (c == 'ζ') {
				appendEntity(html, sb, c, "&zeta;");
			} else if (c == 'η') {
				appendEntity(html, sb, c, "&eta;");
			} else if (c == 'θ') {
				appendEntity(html, sb, c, "&theta;");
			} else if (c == 'ι') {
				appendEntity(html, sb, c, "&iota;");
			} else if (c == 'κ') {
				appendEntity(html, sb, c, "&kappa;");
			} else if (c == 'λ') {
				appendEntity(html, sb, c, "&lambda;");
			} else if (c == 'μ') {
				appendEntity(html, sb, c, "&mu;");
			} else if (c == 'ν') {
				appendEntity(html, sb, c, "&nu;");
			} else if (c == 'ξ') {
				appendEntity(html, sb, c, "&xi;");
			} else if (c == 'ο') {
				appendEntity(html, sb, c, "&omicron;");
			} else if (c == 'π') {
				appendEntity(html, sb, c, "&pi;");
			} else if (c == 'ρ') {
				appendEntity(html, sb, c, "&rho;");
			} else if (c == 'ς') {
				appendEntity(html, sb, c, "&sigmaf;");
			} else if (c == 'σ') {
				appendEntity(html, sb, c, "&sigma;");
			} else if (c == 'τ') {
				appendEntity(html, sb, c, "&tau;");
			} else if (c == 'υ') {
				appendEntity(html, sb, c, "&upsilon;");
			} else if (c == 'φ') {
				appendEntity(html, sb, c, "&phi;");
			} else if (c == 'χ') {
				appendEntity(html, sb, c, "&chi;");
			} else if (c == 'ψ') {
				appendEntity(html, sb, c, "&psi;");
			} else if (c == 'ω') {
				appendEntity(html, sb, c, "&omega;");
			} else if (c == 'ϑ') {
				appendEntity(html, sb, c, "&thetasym;");
			} else if (c == 'ϒ') {
				appendEntity(html, sb, c, "&upsih;");
			} else if (c == 'ϖ') {
				appendEntity(html, sb, c, "&piv;");
			} else if (c == ' ') {
				appendEntity(html, sb, c, "&ensp;");
			} else if (c == ' ') {
				appendEntity(html, sb, c, "&emsp;");
			} else if (c == '—') {
				appendEntity(html, sb, c, "&mdash;");
			} else if (c == '‘') {
				appendEntity(html, sb, c, "&lsquo;");
			} else if (c == '’') {
				appendEntity(html, sb, c, "&rsquo;");
			} else if (c == '‚') {
				appendEntity(html, sb, c, "&sbquo;");
			} else if (c == '“') {
				appendEntity(html, sb, c, "&ldquo;");
			} else if (c == '”') {
				appendEntity(html, sb, c, "&rdquo;");
			} else if (c == '„') {
				appendEntity(html, sb, c, "&bdquo;");
			} else if (c == '†') {
				appendEntity(html, sb, c, "&dagger;");
			} else if (c == '‡') {
				appendEntity(html, sb, c, "&Dagger;");
			} else if (c == '•') {
				appendEntity(html, sb, c, "&bull;");
			} else if (c == '…') {
				appendEntity(html, sb, c, "&hellip;");
			} else if (c == '‰') {
				appendEntity(html, sb, c, "&permil;");
			} else if (c == '′') {
				appendEntity(html, sb, c, "&prime;");
			} else if (c == '″') {
				appendEntity(html, sb, c, "&Prime;");
			} else if (c == '‹') {
				appendEntity(html, sb, c, "&lsaquo;");
			} else if (c == '›') {
				appendEntity(html, sb, c, "&rsaquo;");
			} else if (c == '‾') {
				appendEntity(html, sb, c, "&oline;");
			} else if (c == '⁄') {
				appendEntity(html, sb, c, "&frasl;");
			} else if (c == '€') {
				appendEntity(html, sb, c, "&euro;");
			} else if (c == 'ℑ') {
				appendEntity(html, sb, c, "&image;");
			} else if (c == '℘') {
				appendEntity(html, sb, c, "&weierp;");
			} else if (c == 'ℜ') {
				appendEntity(html, sb, c, "&real;");
			} else if (c == '™') {
				appendEntity(html, sb, c, "&trade;");
			} else if (c == 'ℵ') {
				appendEntity(html, sb, c, "&alefsym;");
			} else if (c == '←') {
				appendEntity(html, sb, c, "&larr;");
			} else if (c == '↑') {
				appendEntity(html, sb, c, "&uarr;");
			} else if (c == '→') {
				appendEntity(html, sb, c, "&rarr;");
			} else if (c == '↓') {
				appendEntity(html, sb, c, "&darr;");
			} else if (c == '↔') {
				appendEntity(html, sb, c, "&harr;");
			} else if (c == '↵') {
				appendEntity(html, sb, c, "&crarr;");
			} else if (c == '⇐') {
				appendEntity(html, sb, c, "&lArr;");
			} else if (c == '⇑') {
				appendEntity(html, sb, c, "&uArr;");
			} else if (c == '⇒') {
				appendEntity(html, sb, c, "&rArr;");
			} else if (c == '⇓') {
				appendEntity(html, sb, c, "&dArr;");
			} else if (c == '⇔') {
				appendEntity(html, sb, c, "&hArr;");
			} else if (c == '∀') {
				appendEntity(html, sb, c, "&forall;");
			} else if (c == '∂') {
				appendEntity(html, sb, c, "&part;");
			} else if (c == '∃') {
				appendEntity(html, sb, c, "&exist;");
			} else if (c == '∅') {
				appendEntity(html, sb, c, "&empty;");
			} else if (c == '∇') {
				appendEntity(html, sb, c, "&nabla;");
			} else if (c == '∈') {
				appendEntity(html, sb, c, "&isin;");
			} else if (c == '∉') {
				appendEntity(html, sb, c, "&notin;");
			} else if (c == '∋') {
				appendEntity(html, sb, c, "&ni;");
			} else if (c == '∏') {
				appendEntity(html, sb, c, "&prod;");
			} else if (c == '∑') {
				appendEntity(html, sb, c, "&sum;");
			} else if (c == '−') {
				appendEntity(html, sb, c, "&minus;");
			} else if (c == '∗') {
				appendEntity(html, sb, c, "&lowast;");
			} else if (c == '√') {
				appendEntity(html, sb, c, "&radic;");
			} else if (c == '∝') {
				appendEntity(html, sb, c, "&prop;");
			} else if (c == '∞') {
				appendEntity(html, sb, c, "&infin;");
			} else if (c == '∠') {
				appendEntity(html, sb, c, "&ang;");
			} else if (c == '∧') {
				appendEntity(html, sb, c, "&and;");
			} else if (c == '∨') {
				appendEntity(html, sb, c, "&or;");
			} else if (c == '∩') {
				appendEntity(html, sb, c, "&cap;");
			} else if (c == '∪') {
				appendEntity(html, sb, c, "&cup;");
			} else if (c == '∫') {
				appendEntity(html, sb, c, "&int;");
			} else if (c == '∴') {
				appendEntity(html, sb, c, "&there4;");
			} else if (c == '∼') {
				appendEntity(html, sb, c, "&sim;");
			} else if (c == '≅') {
				appendEntity(html, sb, c, "&cong;");
			} else if (c == '≈') {
				appendEntity(html, sb, c, "&asymp;");
			} else if (c == '≠') {
				appendEntity(html, sb, c, "&ne;");
			} else if (c == '≡') {
				appendEntity(html, sb, c, "&equiv;");
			} else if (c == '≤') {
				appendEntity(html, sb, c, "&le;");
			} else if (c == '≥') {
				appendEntity(html, sb, c, "&ge;");
			} else if (c == '⊂') {
				appendEntity(html, sb, c, "&sub;");
			} else if (c == '⊃') {
				appendEntity(html, sb, c, "&sup;");
			} else if (c == '⊄') {
				appendEntity(html, sb, c, "&nsub;");
			} else if (c == '⊆') {
				appendEntity(html, sb, c, "&sube;");
			} else if (c == '⊇') {
				appendEntity(html, sb, c, "&supe;");
			} else if (c == '⊕') {
				appendEntity(html, sb, c, "&oplus;");
			} else if (c == '⊗') {
				appendEntity(html, sb, c, "&otimes;");
			} else if (c == '⊥') {
				appendEntity(html, sb, c, "&perp;");
			} else if (c == '⋅') {
				appendEntity(html, sb, c, "&sdot;");
			} else if (c == '⌈') {
				appendEntity(html, sb, c, "&lceil;");
			} else if (c == '⌉') {
				appendEntity(html, sb, c, "&rceil;");
			} else if (c == '⌊') {
				appendEntity(html, sb, c, "&lfloor;");
			} else if (c == '⌋') {
				appendEntity(html, sb, c, "&rfloor;");
			} else if (c == '◊') {
				appendEntity(html, sb, c, "&loz;");
			} else if (c == '♠') {
				appendEntity(html, sb, c, "&spades;");
			} else if (c == '♣') {
				appendEntity(html, sb, c, "&clubs;");
			} else if (c == '♥') {
				appendEntity(html, sb, c, "&hearts;");
			} else if (c == '♦') {
				appendEntity(html, sb, c, "&diams;");
			} else if (c == '〈') {
				appendEntity(html, sb, c, "&lang;");
			} else // </editor-fold>
			if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
				// A character outside the BMP, such as an emoji, takes two chars;
				// escape the code point they encode, not each half on its own
				sb.append("&#");
				sb.append(Character.toCodePoint(c, s.charAt(i + 1)));
				sb.append(';');
				i++;
			} else if (Character.UnicodeBlock.of(c) != Character.UnicodeBlock.BASIC_LATIN) {
				sb.append("&#");
				sb.append((int) c);
				sb.append(';');
			} else {
				sb.append(c);
			}
			prevChar = c;
		}
		return sb.toString();
	}

	private static void appendEntity(boolean html, StringBuilder sb, char c, String htmlEntity) {
		if (c == ' ') {
			if (html) {
				sb.append("&nbsp;");
			} else {
				sb.append(" ");
			}
		} else if (html) {
			sb.append(htmlEntity);
		} else {
			sb.append("&#");
			sb.append((int) c);
			sb.append(';');
		}
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="JavaScript String escaping">
	/**
	 * Escape characters for use in javascript code.
	 *
	 * @param text text to escape
	 * @return escaped text
	 */
	public static String javascriptStringEscape(String text) {
		return javascriptStringEscape(text, '"', false);
	}

	/**
	 * Escape characters for use in javascript code.
	 *
	 * @param text text to escape
	 * @param escapeChar escape character, either apostrophe or quote.
	 * @param htmlEscape if true, will escape html entities.
	 * @return escaped text
	 */
	public static String javascriptStringEscape(String text, char escapeChar, boolean htmlEscape) {
		StringBuilder sb = new StringBuilder();
		for (char c : text.toCharArray()) {
			if (c == '\\') {
				sb.append('\\').append('\\');
			} else if (c == '\r') {
				sb.append('\\').append('r');
			} else if (c == '\n') {
				sb.append('\\').append('n');
			} else if (c == '\t') {
				sb.append('\\').append('t');
			} else if (c == escapeChar) {
				sb.append('\\');
				sb.append(escapeChar);
			} else if (c <= (char) 0x1F || c >= (char) 0x7F || (htmlEscape && (c == '<' || c == '>' || c == '&'))) {
				String s = Integer.toString((int) c, 16);
				sb.append('\\').append('u');
				for (int i = 0; i < 4 - s.length(); i++) {
					sb.append('0');
				}
				sb.append(s);
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="URLEncoding">
	/**
	 * Encodes a String for use in a URL
	 *
	 * @param string text to escape
	 * @return escaped text
	 */
	public static String urlEncode(String string) {
		try {
			return URLEncoder.encode(string, "UTF-8");
		} catch (Exception e) {
			return string;
		}
	}

	/**
	 * Decodes a string from a URL
	 *
	 * @param string text to unescape
	 * @return unescaped text
	 */
	public static String urlDecode(String string) {
		try {
			return URLDecoder.decode(string, "UTF-8");
		} catch (Exception e) {
			return string;
		}
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Date Format">
	/**
	 * Get the SimpleDateFormat for HTTP.
	 *
	 * @return a SimpleDateFormat
	 */
	public static SimpleDateFormat getSimpleDateFormat() {
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z");
		simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		return simpleDateFormat;
	}
	// </editor-fold>
}
