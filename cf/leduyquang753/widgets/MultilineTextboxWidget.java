package cf.leduyquang753.widgets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
//import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.lwjgl.opengl.GL11;

import com.google.common.base.Predicates;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.MainWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.IRenderable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Multiline text box – A scrollable text box that accepts {@code \n}'s.
 * Currently it dooesn't support formatters.
 */
public class MultilineTextboxWidget extends Widget implements IRenderable, IGuiEventListener {
	private final FontRenderer font;
	private String text = "";
	private int maxLength = 65536;
	private int caretPosition = 0;
	private int selectionCaretPosition = 0;
	/**
	 * Used to blink the caret.
	 */
	private long caretTime = 0;
	/**
	 * The last time the screen was rendered. Used to blink the caret.
	 */
	private long lastTick = -1;
	private boolean drawBackground = true;
	private boolean canLoseFocus = true;
	private boolean editable = true;
	private boolean shiftPressed;
	private int scrollPosition = 0;
	private int textColor = 14737632;
	private int textColorUneditable = 7368816;
	/**
	 * The text displayed when the box is empty.
	 */
	private String hint;
	private Consumer<String> responder;
	private Predicate<String> validator = Predicates.alwaysTrue();
	//private BiFunction<String, Integer, String> formatter = (string, n) -> string;
	/**
	 * Broken down lines to the box's width.
	 */
	private ArrayList<CacheLine> layoutCache = new ArrayList<>();
	private int oldHorizontalCaretPosition = -1;
	private boolean dragging = false, draggingScrollbar = false;
	private int scrollbarYBegin = 0;
	
	/**
	 * The distance from the box's boundaries to the text.
	 */
	private static final int padding = 5;
	
	public MultilineTextboxWidget(FontRenderer font, int left, int top, int width, int height, String narrationMessage) {
		this(font, left, top, width, height, null, narrationMessage);
	}
	
	public MultilineTextboxWidget(FontRenderer font, int left, int top, int width, int height, @Nullable MultilineTextboxWidget boxToGetString, String narrationMessage) {
		super(left, top, width, height, narrationMessage);
		this.font = font;
		if (boxToGetString != null) setText(boxToGetString.getText());
		buildLayoutCache();
	}
	
	public void setResponder(Consumer<String> responder) {
		this.responder = responder;
	}
	
	/*public void setFormatter(BiFunction<String, Integer, String> formatter) {
		this.formatter = formatter;
	}*/
	
	public void tick() {}
	
	@Override
	protected String getNarrationMessage() {
		String message = getMessage();
		return message.isEmpty() ? "" : I18n.format("gui.narrate.editBox", message, text);
	}
	
	public void setText(String text) {
		if (!validator.test(text)) return;
		this.text = text.length() > maxLength ? text.substring(0, maxLength) : text;
		moveCaretToEnd();
		moveSelectionCaretTo(caretPosition);
		onTextChange(text);
	}
	
	public String getText() {
		return text;
	}
	
	public String getSelectedText() {
		int selectionStart = caretPosition < selectionCaretPosition ? caretPosition : selectionCaretPosition;
		int selectionEnd = caretPosition < selectionCaretPosition ? selectionCaretPosition : caretPosition;
		return text.substring(selectionStart, selectionEnd);
	}
	
	public void setValidator(Predicate<String> validator) {
		this.validator = validator;
	}
	
	private boolean isCharacterAllowed(char c) {
		return (c != 167 && c >= ' ' && c != 127) || c == '\n';
	}
	
	private String filterAllowedCharacters(String text) {
		String result = "";
		for (char c : text.toCharArray()) {
			if (isCharacterAllowed(c)) result += c;
		}
		return result;
	}
	
	public void insertText(String textToInsert) {
		String newText = ""; // s
		textToInsert = filterAllowedCharacters(textToInsert); // s1
		int selectionStart = caretPosition < selectionCaretPosition ? caretPosition : selectionCaretPosition;
		int selectionEnd = caretPosition < selectionCaretPosition ? selectionCaretPosition : caretPosition;
		int availableLength = maxLength - text.length() + selectionEnd - selectionStart;
		if (!text.isEmpty()) newText += text.substring(0, selectionStart);
		int insertionLength;
		if (availableLength < textToInsert.length()) {
			newText += textToInsert.substring(0, availableLength);
			insertionLength = availableLength;
		} else {
			newText += textToInsert;
			insertionLength = textToInsert.length();
		}
		if (!text.isEmpty() && selectionEnd < text.length()) {
			newText += text.substring(selectionEnd);
		}
		if (!validator.test(newText)) return;
		text = newText;
		moveCaretTo(selectionStart + insertionLength);
		moveSelectionCaretTo(caretPosition);
		oldHorizontalCaretPosition = -1;
		onTextChange(text);
	}
	
	private void onTextChange(String newText) {
		if (responder != null) responder.accept(newText);
		nextNarration = Util.milliTime() + 500;
		buildLayoutCache();
		int linePosition = getLineCaretIsOn()*font.FONT_HEIGHT;
		if (linePosition < scrollPosition) scrollTo(linePosition);
		if (linePosition + font.FONT_HEIGHT > scrollPosition+height-2*padding) scrollTo(linePosition+font.FONT_HEIGHT-height+2*padding);
		scrollTo(scrollPosition);
	}
	
	/**
	 * Retrieves the width of the text area. 
	 */
	private int getEffectiveWidth() {
		return width-7-2*padding;
	}
	
	/**
	 * Splits the text into lines to fit into the width of the box.
	 */
	private void buildLayoutCache() {
		String currentLine = "", currentWord = "";
		float currentLineWidth = 0, currentWordWidth = 0;
		int effectiveWidth = getEffectiveWidth();
		boolean alreadyNewLine = false, lineStart = true;
		layoutCache.clear();
		if (text.isEmpty()) {
			layoutCache.add(new CacheLine("", 0));
			return;
		}
		int currentIndex = -1, lineIndex = 0;
		for (char c : text.toCharArray()) {
			currentIndex++;
			switch (c) {
				case ' ':
					if (!alreadyNewLine) {
						currentLine += currentWord;
						currentLineWidth += currentWordWidth;
					}
					currentLine += ' ';
					currentLineWidth += font.getCharWidth(' ');
					currentWord = "";
					currentWordWidth = 0;
					alreadyNewLine = false;
					lineStart = false;
					break;
				case '\n':
					layoutCache.add(new CacheLine(currentLine + (alreadyNewLine ? "" : currentWord), lineIndex));
					lineIndex = currentIndex+1;
					currentLine = "";
					currentLineWidth = 0;
					currentWord = "";
					currentWordWidth = 0;
					alreadyNewLine = false;
					lineStart = true;
					break;
				default:
					float charWidth = font.getCharWidth(c);
					if (alreadyNewLine || lineStart) {
						if (currentLineWidth + charWidth > effectiveWidth) {
							layoutCache.add(new CacheLine(currentLine, lineIndex));
							lineIndex = currentIndex;
							currentLineWidth = charWidth;
							currentLine = c + "";
							alreadyNewLine = true;
						} else {
							currentLine += c;
							currentLineWidth += charWidth;
						}
					} else {
						if (currentLineWidth + currentWordWidth + charWidth > effectiveWidth) {
							layoutCache.add(new CacheLine(currentLine, lineIndex));
							lineIndex = currentIndex-currentWord.length();
							currentLine = currentWord+c;
							currentLineWidth = currentWordWidth+charWidth;
							alreadyNewLine = true;
						} else {
							currentWord += c;
							currentWordWidth += charWidth;
						}
					}
			}
		}
		layoutCache.add(new CacheLine(currentLine+(alreadyNewLine ? "" : currentWord), lineIndex));
	}

	private void deleteText(int amount) {
		if (Screen.hasControlDown()) deleteWords(amount); else deleteChars(amount);
	}
	
	public void deleteWords(int amount) {
		if (text.isEmpty()) return;
		if (selectionCaretPosition != caretPosition) {
			insertText("");
			return;
		}
		deleteChars(getWordPosition(amount) - caretPosition);
	}
	
	public void deleteChars(int amount) {
		if (text.isEmpty()) return;
		if (selectionCaretPosition != caretPosition) {
			insertText("");
			return;
		}
		boolean reversed = amount < 0;
		int deletionStart = reversed? caretPosition + amount : caretPosition;
		int deletionEnd = reversed? caretPosition : caretPosition + amount;
		String newText = "";
		if (deletionStart > -1) newText = text.substring(0, deletionStart);
		if (deletionEnd < text.length()) newText += text.substring(deletionEnd);
		if (!validator.test(newText)) return;
		text = newText;
		if (reversed) moveCaretBy(amount);
		oldHorizontalCaretPosition = -1;
		onTextChange(text);
	}
	
	public int getWordPosition(int indexFromCaret) {
		return getWordPosition(indexFromCaret, caretPosition);
	}
	
	private int getWordPosition(int indexFrom, int position) {
		return getWordPosition(indexFrom, position, true);
	}
	
	private boolean isWhitespace(char c) {
		return (c == ' ' || c == '\n');
	}
	
	private int getWordPosition(int indexFrom, int position, boolean skipConsecutiveWhitespaces) {
		boolean reversed = indexFrom < 0;
		indexFrom = Math.abs(indexFrom);
		for (int i = 0; i < indexFrom; i++) {
			if (reversed) {
				while (skipConsecutiveWhitespaces && position > 0 && isWhitespace(text.charAt(position-1))) position--;
				while (position > 0 && !isWhitespace(text.charAt(position-1))) position--;
				continue;
			}
			int textLength = text.length();
			if ((position = text.indexOf(32, position)) == -1) {
				position = textLength;
				continue;
			}
			while (skipConsecutiveWhitespaces && position < textLength && isWhitespace(text.charAt(position))) position++;
		}
		return position;
	}
	
	public void moveCaretBy(int amount) {
		userMoveCaretTo(caretPosition+amount);
	}
	
	public void userMoveCaretTo(int position) {
		moveCaretTo(position);
		if (!shiftPressed) moveSelectionCaretTo(position);
		caretTime = -1;
		onTextChange(text);
	}
	
	public void moveCaretTo(int position) {
		caretPosition = MathHelper.clamp(position, 0, text.length());
	}
	
	public void moveCaretToStart() {
		userMoveCaretTo(0);
	}
	
	public void moveCaretToEnd() {
		userMoveCaretTo(text.length());
	}
	
	private int getLineCaretIsOn() {
		int line = Arrays.binarySearch(layoutCache.toArray(new CacheLine[layoutCache.size()]), new CacheLine("", caretPosition), new LineComparator());
		return line < 0 ? Math.min(layoutCache.size()-1, -line-2) : line;
	}
	
	@Override
	public boolean keyPressed(int key, int arg2, int arg3) {
		if (!canConsumeInput()) return false;
		shiftPressed = Screen.hasShiftDown();
		if (Screen.isSelectAll(key)) {
			moveCaretToEnd();
			moveSelectionCaretTo(0);
			oldHorizontalCaretPosition = -1;
			return true;
		} else if (Screen.isCopy(key)) {
			Minecraft.getInstance().keyboardListener.setClipboardString(getSelectedText());
			return true;
		} else if (Screen.isPaste(key)) {
			if (editable) insertText(Minecraft.getInstance().keyboardListener.getClipboardString());
			return true;
		} else if (Screen.isCut(key)) {
			Minecraft.getInstance().keyboardListener.setClipboardString(getSelectedText());
			if (editable) insertText("");
			return true;
		}
		int currentLine, destinationLine;
		switch (key) {
			case 257: // Enter
			case 335: // Keypad Enter
				charTyped('\n', key);
				return true;
			case 263: // ←
				if (Screen.hasControlDown()) userMoveCaretTo(getWordPosition(-1));
				else moveCaretBy(-1);
				oldHorizontalCaretPosition = -1;
				return true;
			case 262: // →
				if (Screen.hasControlDown()) userMoveCaretTo(getWordPosition(1));
				else moveCaretBy(1);
				oldHorizontalCaretPosition = -1;
				return true;
			case 265: // ↑
				currentLine = getLineCaretIsOn();
				if (currentLine == 0) moveCaretToStart();
				else if (oldHorizontalCaretPosition == -1) {
					oldHorizontalCaretPosition = caretPosition - layoutCache.get(currentLine).getStartIndex();
					userMoveCaretTo(layoutCache.get(currentLine-1).getStartIndex() + Math.min(layoutCache.get(currentLine-1).getText().length(), oldHorizontalCaretPosition));
				}
				else userMoveCaretTo(layoutCache.get(currentLine-1).getStartIndex() + Math.min(layoutCache.get(currentLine-1).getText().length(), oldHorizontalCaretPosition));
				return true;
			case 264: // ↓
				currentLine = getLineCaretIsOn();
				if (currentLine == layoutCache.size()-1) moveCaretToEnd();
				else if (oldHorizontalCaretPosition == -1)  {
					oldHorizontalCaretPosition = caretPosition - layoutCache.get(currentLine).getStartIndex();
					userMoveCaretTo(layoutCache.get(currentLine+1).getStartIndex() + Math.min(layoutCache.get(currentLine+1).getText().length(), oldHorizontalCaretPosition));
				}
				else userMoveCaretTo(layoutCache.get(currentLine+1).getStartIndex() + Math.min(layoutCache.get(currentLine+1).getText().length(), oldHorizontalCaretPosition));
				return true;
			case 259: // ⌫
				if (editable) {
					shiftPressed = false;
					deleteText(-1);
					shiftPressed = Screen.hasShiftDown();
				}
				return true;
			case 261: // ⌦
				if (editable) {
					shiftPressed = false;
					deleteText(1);
					shiftPressed = Screen.hasShiftDown();
				}
				return true;
			case 268: // Home
				if (Screen.hasControlDown()) moveCaretToStart();
				else userMoveCaretTo(layoutCache.get(getLineCaretIsOn()).getStartIndex());
				return true;
			case 269: // End
				if (Screen.hasControlDown()) moveCaretToEnd();
				else {
					CacheLine line = layoutCache.get(getLineCaretIsOn());
					userMoveCaretTo(line.getStartIndex()+line.getText().length());
				}
				return true;
			case 266: // PageUp
				currentLine = getLineCaretIsOn();
				destinationLine = Math.max(0, currentLine-height/font.FONT_HEIGHT);
				if (oldHorizontalCaretPosition == -1) {
					oldHorizontalCaretPosition = caretPosition - layoutCache.get(currentLine).getStartIndex();
					userMoveCaretTo(layoutCache.get(destinationLine).getStartIndex() + Math.min(layoutCache.get(destinationLine).getText().length(), oldHorizontalCaretPosition));
				}
				else userMoveCaretTo(layoutCache.get(destinationLine).getStartIndex() + Math.min(layoutCache.get(destinationLine).getText().length(), oldHorizontalCaretPosition)); 
				return true;
			case 267: // PageDown
				currentLine = getLineCaretIsOn();
				destinationLine = Math.min(layoutCache.size()-1, currentLine+height/font.FONT_HEIGHT);
				if (oldHorizontalCaretPosition == -1) {
					oldHorizontalCaretPosition = caretPosition - layoutCache.get(currentLine).getStartIndex();
					userMoveCaretTo(layoutCache.get(destinationLine).getStartIndex() + Math.min(layoutCache.get(destinationLine).getText().length(), oldHorizontalCaretPosition));
				}
				else userMoveCaretTo(layoutCache.get(destinationLine).getStartIndex() + Math.min(layoutCache.get(destinationLine).getText().length(), oldHorizontalCaretPosition));
				return true;
		}
		return false;
	}
	
	public boolean canConsumeInput() {
		return isVisible() && isFocused() && isEditable();
	}
	
	@Override
	public boolean charTyped(char character, int key) {
		if (!canConsumeInput()) return false;
		if (isCharacterAllowed(character)) {
			if (isEditable()) insertText(Character.toString(character));
			return true;
		}
		return false;
	}
	
	private int getIndexFromMousePosition(double mouseX, double mouseY) {
		CacheLine line = layoutCache.get(MathHelper.clamp(((int)Math.floor(mouseY) - y - padding + scrollPosition) / font.FONT_HEIGHT, 0, layoutCache.size()-1));
		float currentWidth = 0;
		double relativeX = mouseX - x - padding;
		int destination = line.getStartIndex();
		for (char c : line.getText().toCharArray()) {
			if ((currentWidth = currentWidth + font.getCharWidth(c)) > relativeX) break;
			destination++;
		}
		return destination;
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!isVisible()) return false;
		shiftPressed = Screen.hasShiftDown();
		boolean isInBounds = mouseX >= x && mouseX < x+width && mouseY >= y && mouseY < y+height;
		if (canLoseFocus) setFocus(isInBounds);
		if (isFocused() && isInBounds && button == 0) {
			// Check for scrollbar.
			if (mouseX > x+width-8 && mouseX < x+width-2 && mouseY > y+1 && mouseY < y+height-2) {
				if (mouseY < y+2+getScrollbarPosition()) scrollTo(scrollPosition-height+4);
				else if (mouseY > y+1+getScrollbarPosition()+getScrollbarHeight()) scrollTo(scrollPosition+height-4);
				else {
					dragging = true;
					draggingScrollbar = true;
					scrollbarYBegin = getScrollbarPosition()-(int)mouseY;
				}
			// Check for text area.
			} else if (mouseX >= x+padding && mouseX < x+width-7-padding && mouseY >= y+padding && mouseY < y+width-padding) {
				userMoveCaretTo(getIndexFromMousePosition(mouseX, mouseY));
				dragging = true;
				draggingScrollbar = false;
				oldHorizontalCaretPosition = -1;
			} else dragging = false;
			return true;
		} else return false;
	}
	
	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double originalMouseX, double originalMouseY) {
		if (!isVisible() || !dragging) return false;
		shiftPressed = Screen.hasShiftDown();
		if (isFocused() && button == 0) {
			// Check for scrollbar.
			if (draggingScrollbar) {
				if (getScrollbarHeight() < height-4) scrollTo((int) ((font.FONT_HEIGHT * layoutCache.size() -height+4) * (scrollbarYBegin+mouseY) / (height-4-getScrollbarHeight())));
			// Check for text area.
			} else {
				if (mouseY < y+padding) scrollTo((int) (scrollPosition - (y+padding-mouseY)/5));
				if (mouseY > y+height-padding+1) scrollTo((int) (scrollPosition + (mouseY-y-height+padding-1)/5));
				moveCaretTo(getIndexFromMousePosition(mouseX, MathHelper.clamp(mouseY, y+padding, y+height-padding-1)));
				caretTime = -1;
				oldHorizontalCaretPosition = -1;
				onTextChange(text);
			}	
			return true;
		} else return false;
	}
	
	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
		shiftPressed = Screen.hasShiftDown();
		boolean wasDragging = dragging;
		dragging = false;
		return wasDragging;
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!isVisible()) return false;
		boolean isInBounds = mouseX >= x && mouseX < x+width && mouseY >= y && mouseY < y+height;
		if (canLoseFocus) setFocus(isInBounds);
		if (isFocused() && isInBounds) {
			scrollTo((int) (scrollPosition + font.FONT_HEIGHT*Math.floor(-amount)));
			return true;
		}
		return false;
	}
	
	public void setFocus(boolean focused) {
		super.setFocused(focused);
	}
	
	@Override
	public void renderButton(int mouseX, int mouseY, float partialTicks) {
		if (!isVisible()) return;
		if (shouldDrawBackground()) {
			fill(x, y, x+width, y+height, isFocused()? -1 : -6250336);
			fill(x+1, y+1, x+width-1, y+height-1, -16777216);
		}
		int textColor = editable? this.textColor : textColorUneditable;
		MainWindow window = Minecraft.getInstance().func_228018_at_();
		double scaleFactor = window.getGuiScaleFactor();
		// Text.
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glScissor((int)((x+padding)*scaleFactor), (int)((window.getScaledHeight()-y-height+padding)*scaleFactor), (int)((width-2*padding-7)*scaleFactor), (int)((height-2*padding)*scaleFactor));
		boolean havingSelection = selectionCaretPosition != caretPosition;
		int selectionStart = 0, selectionEnd = text.length();
		if (havingSelection) {
			selectionStart = Math.min(selectionCaretPosition, caretPosition);
			selectionEnd = Math.max(selectionCaretPosition, caretPosition);
		}
		int firstLine = MathHelper.clamp(scrollPosition/font.FONT_HEIGHT, 0, layoutCache.size()-1);
		int lineAfterLast = MathHelper.clamp((scrollPosition+height-5)/font.FONT_HEIGHT+1, 1, layoutCache.size());
		if (text.isEmpty()) font.drawString(hint, x+padding, y+padding, -8355712); 
		else for (int i = firstLine; i < lineAfterLast; i++) {
			CacheLine line = layoutCache.get(i);
			int top = y+padding-scrollPosition+i*font.FONT_HEIGHT;
			font.drawStringWithShadow(line.getText(), x+padding, top, textColor);
			if (!havingSelection) continue;
			int[] highlighted = getHighlightedPortion(line.getStartIndex(), line.getStartIndex()+line.getText().length(), selectionStart, selectionEnd);
			if (highlighted[0] == -1) continue;
			int left = x+padding+font.getStringWidth(line.getText().substring(0, highlighted[0]-line.getStartIndex()));
			drawHighlightBox(left, top, left+font.getStringWidth(line.getText().substring(highlighted[0]-line.getStartIndex(), highlighted[1]-line.getStartIndex())), top+font.FONT_HEIGHT);
		}
		// Caret.
		int caretLine = getLineCaretIsOn();
		long currentTime = System.currentTimeMillis();
		if (caretTime == -1) caretTime = 0; else if (lastTick != -1) caretTime += currentTime-lastTick;
		lastTick = currentTime;
		if (isFocused() && caretTime % 1000 < 500 && caretLine >= firstLine && caretLine < lineAfterLast) {
			CacheLine line = layoutCache.get(caretLine);
			int left = MathHelper.clamp(x+padding+font.getStringWidth(line.getText().substring(0, caretPosition-line.getStartIndex())), x+padding, x+width-padding-1);
			int top = y+padding-scrollPosition+caretLine*font.FONT_HEIGHT;
			fill(left, top, left+1, top+font.FONT_HEIGHT, -3092272);
		}
		// Scrollbar
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		fill(x+width-7, y+2, x+width-2, y-2+height, -16777216);
		if (getScrollbarHeight() != height-4) {
			fill(x+width-7, y+2+getScrollbarPosition(), x+width-2, y+2+getScrollbarPosition()+getScrollbarHeight(), -8355712);
			fill(x+width-7, y+2+getScrollbarPosition(), x+width-3, y+1+getScrollbarPosition()+getScrollbarHeight(), -4144960);
		}
	}
	
	private int[] getHighlightedPortion(int startIndex, int endIndex, int selectionStart, int selectionEnd) {
		int maxOfMin = Math.max(startIndex, selectionStart);
		int minOfMax = Math.min(endIndex, selectionEnd);
		if (maxOfMin >= minOfMax) return new int[] {-1};
		return new int[] {maxOfMin, minOfMax};
	}
	
	private void drawHighlightBox(int left, int top, int right, int bottom) {
		int swappingNumber;
        if (left < right) {
            swappingNumber = left;
            left = right;
            right = swappingNumber;
        }
        if (top < bottom) {
            swappingNumber = top;
            top = bottom;
            bottom = swappingNumber;
        }
        if (right > this.x + this.width) {
            right = this.x + this.width;
        }
        if (left > this.x + this.width) {
            left = this.x + this.width;
        }
        Tessellator tesselator = Tessellator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuffer();
        RenderSystem.color4f(0.0f, 0.0f, 255.0f, 255.0f);
        RenderSystem.disableTexture();
        RenderSystem.enableColorLogicOp();
        RenderSystem.logicOp(GlStateManager.LogicOp.OR_REVERSE);
        bufferBuilder.begin(7, DefaultVertexFormats.POSITION);
        bufferBuilder.func_225582_a_(left, bottom, 0.0).endVertex(); // func_22582_a_ is vertex.
        bufferBuilder.func_225582_a_(right, bottom, 0.0).endVertex();
        bufferBuilder.func_225582_a_(right, top, 0.0).endVertex();
        bufferBuilder.func_225582_a_(left, top, 0.0).endVertex();
        tesselator.draw(); // end
        RenderSystem.disableColorLogicOp();
        RenderSystem.enableTexture();
	}
	
	public void setMaxLength(int maxLength) {
		this.maxLength = maxLength;
		if (text.length() > maxLength) {
			text = text.substring(0, maxLength);
			onTextChange(text);
		}
	}
	
	public int getMaxLength() {
		return maxLength;
	}
	
	public int getCaretPosition() {
		return caretPosition;
	}
	
	private boolean shouldDrawBackground() {
		return drawBackground;
	}
	
	public void setShouldDrawBackground(boolean whether) {
		drawBackground = whether;
	}
	
	public void setTextColor(int color) {
		textColor = color;
	}
	
	public void setTextColorUneditable(int color) {
		textColorUneditable = color;
	}
	
	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
	}
	
	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return visible && mouseX >= x && mouseX < x+width && mouseY >= y && mouseY < y+height;
	}
	
	@Override
	protected void onFocusedChanged(boolean focused) {
		if (focused) caretTime = -1;
	}
	
	private boolean isEditable() {
		return editable;
	}
	
	public void setEditable(boolean editable) {
		this.editable = editable;
	}
	
	public void moveSelectionCaretTo(int position) {
		selectionCaretPosition = MathHelper.clamp(position, 0, text.length());
	}
	
	public void setCanLoseFocus(boolean canLoseFocus) {
		this.canLoseFocus = canLoseFocus;
	}
	
	public boolean isVisible() {
		return visible;
	}
	
	public void setVisible(boolean visible) {
		this.visible = visible;
	}
	
	public void setHint(@Nullable String hint) {
		this.hint = hint;
	}
	
	public int getMaxScroll() {
		return Math.max(0, layoutCache.size()*font.FONT_HEIGHT-height+2*padding);
	}
	
	public void scrollTo(int position) {
		scrollPosition = MathHelper.clamp(position, 0, getMaxScroll());
	}
	
	public int getScrollbarHeight() {
		return Math.max(3, (int)(Math.min(1, (double)(height-2*padding)/(double)(layoutCache.size()*font.FONT_HEIGHT))*(height-4)));
	}
	
	public int getScrollbarPosition() {
		int maxScroll = getMaxScroll();
		if (maxScroll == 0) return 0;
		return (height-4-getScrollbarHeight()) * scrollPosition / maxScroll;
	}
	
	private class CacheLine {
		private final String text;
		private final int startIndex;
		
		public CacheLine(String text, int startIndex) {
			this.text = text;
			this.startIndex = startIndex;
		}
		
		public String getText() {
			return text;
		}
		
		public int getStartIndex() {
			return startIndex;
		}
	}
	
	private class LineComparator implements Comparator<CacheLine> {
		@Override
		public int compare(CacheLine l1, CacheLine l2) {
			return new Integer(l1.getStartIndex()).compareTo(l2.getStartIndex());
		}
	}
}