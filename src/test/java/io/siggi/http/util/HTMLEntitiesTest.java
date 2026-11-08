package io.siggi.http.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HTMLEntitiesTest {

	@Test
	void characterOutsideBmpIsOneReference() {
		assertEquals("a&#128512;b", HTMLUtils.htmlentities("a😀b"));
		assertEquals("a&#128512;b", HTMLUtils.xmlentities("a😀b"));
	}

	@Test
	void adjacentCharactersOutsideBmp() {
		assertEquals("&#128512;&#127881;", HTMLUtils.htmlentities("😀🎉"));
	}

	@Test
	void characterOutsideBmpAtEnd() {
		assertEquals("x&#128512;", HTMLUtils.htmlentities("x😀"));
	}

	@Test
	void unpairedSurrogatesAreLeftAsTheyWere() {
		assertEquals("a&#55357;b", HTMLUtils.htmlentities("a\uD83Db"));
		assertEquals("a&#56832;b", HTMLUtils.htmlentities("a\uDE00b"));
		assertEquals("a&#55357;", HTMLUtils.htmlentities("a\uD83D"));
	}

	@Test
	void otherEscapingIsUnchanged() {
		assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;", HTMLUtils.htmlentities("<a href=\"x\">&</a>"));
		assertEquals("caf&eacute; &#26085;", HTMLUtils.htmlentities("café 日"));
		assertEquals("caf&#233;", HTMLUtils.xmlentities("café"));
	}
}
