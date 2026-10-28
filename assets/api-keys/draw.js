// When the API keys page draws its code (#436, #590), without the DOM, so Node tests it
// (`draw.test.mjs`): the code for the keys and the words, sealed with them, or clear without them.
// The code on screen goes as soon as what it should hold changes, before a new one is sealed, so a
// clear code never stays on screen while a sealed one is made, nor after sealing fails; a code
// sealed after another was asked for is dropped; the same keys and words are not sealed again, with
// a new salt, at a keystroke that changes nothing in the code; and what is said of the code is the
// latest asked for, also when it changed while the code was being sealed (a key left out, say).

/**
 * What draws the code: [seal] seals a setup code's text with words (a promise of the sealed code),
 * [show] shows a code (its content, whether it is sealed, and [describe], given with it, which says
 * what it holds), [hide] removes the code shown, and [failed] says that sealing failed.
 */
export function codeDrawer({ seal, show, hide, failed }) {
  let drawing = 0;
  let drawn = null;
  let describing = null;
  const key = (text, words) => JSON.stringify([text, words]);
  return {
    /** Whether the code shown, or being sealed, holds [text], sealed with [words] (null: clear). */
    holds(text, words) {
      return drawn === key(text, words);
    },

    /**
     * Draws [text], sealed with [words] (null: clear), unless that is what the code holds already;
     * either way, [describe] is what says what it holds once it shows.
     */
    async draw(text, words, describe) {
      describing = describe;
      const what = key(text, words);
      if (what === drawn) return;
      drawn = what;
      const asked = ++drawing;
      hide();
      if (words === null) {
        show(text, false, describing);
        return;
      }
      let sealed;
      try {
        sealed = await seal(text, words);
      } catch {
        if (asked === drawing) {
          drawn = null;
          failed();
        }
        return;
      }
      if (asked === drawing) show(sealed, true, describing);
    },

    /** Removes the code, and drops one still being sealed. */
    clear() {
      drawing++;
      drawn = null;
      hide();
    },
  };
}
