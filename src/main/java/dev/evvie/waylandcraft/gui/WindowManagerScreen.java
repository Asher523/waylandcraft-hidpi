package dev.evvie.waylandcraft.gui;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCPopup;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge.Size;
import dev.evvie.waylandcraft.desktop.DesktopEntry;
import dev.evvie.waylandcraft.grabs.WindowGrab;
import dev.evvie.waylandcraft.mixin.IMouseHandlerMixin;
import dev.evvie.waylandcraft.render.RenderUtils;
import dev.evvie.waylandcraft.render.WindowFramebuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.components.PopupScreen;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

public class WindowManagerScreen extends Screen {
	
	private WaylandCraft wlc;
	
	private SelectorWidget<WLCToplevel> selector;
	private ArrayList<Button> buttons = new ArrayList<Button>();
	private Button grabButton;
	private Button resizeButton;
	private Button hideButton;
	private Button pinButton;
	private Button itemButton;
	private Button helpButton;
	
	private StringWidget captureModeMessage;
	private ImageWidget captureModeSprite;
	
	private boolean overviewMode = false;
	private Button overviewButton;
	private Button fillButton;
	private final ArrayList<OverviewCell> overviewCells = new ArrayList<>();

	private boolean resizeMode = false;
	private WLCToplevel resizeToplevel = null;
	private double resizeLastX = Double.NaN;
	private double resizeLastY = Double.NaN;
	private int resizeWidth = -1;
	private int resizeHeight = -1;
	
	// GUI parameters (in GUI scale coordinates!!)
	private final int margin = 3;
	private final int leftMargin = 30;
	private final int topMargin = 40;
	private int areaWidth;
	private int areaHeight;
	private int guiScale;
	
	private boolean captureModeEnabled = false;
	
	private WLCToplevel focused = null;
	private WLCToplevel lastFocused = null;
	
	// All window elements currently displayed, sorted by depth from bottom-most (root) to top-most (last leaf)
	public ArrayList<WindowElement> windows = new ArrayList<WindowElement>();
	
	private ImplicitGrab implicitGrab = null;
	
	public WindowManagerScreen(WaylandCraft wlc) {
		super(Component.literal("Window Manager"));
		this.wlc = wlc;
	}
	
	@Override
	protected void init() {
		areaWidth = width - margin - leftMargin;
		areaHeight = height - margin - topMargin;
		
		int buttonWidth = width / 3 - 5;
		int buttonHeight = 17;
		
		selector = new SelectorWidget<WLCToplevel>(leftMargin - 1, topMargin - 17, areaWidth + 2, 17) {
			@Override
			public Component titleForElement(WLCToplevel element) {
				return Component.literal(Optional.ofNullable(element.title).or(() -> Optional.ofNullable(element.appID)).orElse(""));
			}
			
			@Override
			public boolean elementDimColor(WLCToplevel element) {
				return !wlc.hasDisplayFor(element);
			}
			
			@Override
			public @Nullable Identifier iconForElement(WLCToplevel element) {
				DesktopEntry entry = wlc.xdgManager.forAppId(element.appID);
				if(entry == null) return null;
				
				Identifier icon = entry.getIcon();
				if(icon == null) return null;
				
				return icon;
			}
		};
		addRenderableWidget(selector);
		
		grabButton = Button.builder(Component.literal("Grab"), this::onGrabPressed)
				.pos(width - buttonWidth - margin + 1, margin)
				.size(buttonWidth, buttonHeight)
				.build();
		buttons.add(grabButton);
		
		resizeButton = Button.builder(Component.literal("Resize"), this::onResizePressed)
				.pos(width / 2 - buttonWidth / 2, margin)
				.size(buttonWidth, buttonHeight)
				.build();
		buttons.add(resizeButton);

		overviewButton = Button.builder(Component.literal("Overview"), this::onOverviewPressed)
				.pos(leftMargin, margin)
				.size(72, buttonHeight)
				.build();
		buttons.add(overviewButton);

		fillButton = Button.builder(Component.literal("Fill"), this::onFillPressed)
				.pos(leftMargin + 76, margin)
				.size(50, buttonHeight)
				.build();
		buttons.add(fillButton);
		
		Component fullscreenComponent = Component.literal("Capture Mode").withColor(ARGB.color(255, 0, 0));
		captureModeMessage = new StringWidget(leftMargin + 18, margin - 1, buttonWidth, buttonHeight, fullscreenComponent, font);
		captureModeSprite = ImageWidget.sprite(15, 15, Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "capture"));
		captureModeSprite.setPosition(leftMargin - 1, margin);
		
		hideButton = SpriteIconButton.builder(Component.literal("Hide"), this::onHidePressed, true)
				.sprite(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "hide"), 15, 15)
				.size(22, 22)
				.build();
		hideButton.setPosition(3, topMargin);
		hideButton.setTooltip(Tooltip.create(Component.literal("Hide")));
		hideButton.setTooltipDelay(Duration.ofMillis(700));
		buttons.add(hideButton);
		
		pinButton = SpriteIconButton.builder(Component.literal("Pin"), this::onPinPressed, true)
				.sprite(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pin"), 15, 15)
				.size(22, 22)
				.build();
		pinButton.setPosition(3, topMargin + 30);
		pinButton.setTooltip(Tooltip.create(Component.literal("Pin")));
		pinButton.setTooltipDelay(Duration.ofMillis(700));
		buttons.add(pinButton);
		
		itemButton = SpriteIconButton.builder(Component.literal("Give Window Item"), this::onItemPressed, true)
				.sprite(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window"), 16, 16)
				.size(22, 22)
				.build();
		itemButton.setPosition(3, topMargin + 60);
		itemButton.setTooltip(Tooltip.create(Component.literal("Give Window Item")));
		itemButton.setTooltipDelay(Duration.ofMillis(700));
		buttons.add(itemButton);
		
		helpButton = SpriteIconButton.builder(Component.literal("Help"), this::onHelpPressed, true)
				.sprite(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "help"), 15, 15)
				.size(22, 22)
				.build();
		helpButton.setPosition(3, height - 22 - margin);
		helpButton.setTooltip(Tooltip.create(Component.literal("Help")));
		helpButton.setTooltipDelay(Duration.ofMillis(700));
		buttons.add(helpButton);
		
		addRenderableWidget(grabButton);
		addRenderableWidget(resizeButton);
		addRenderableWidget(overviewButton);
		addRenderableWidget(fillButton);
		addRenderableWidget(hideButton);
		addRenderableWidget(pinButton);
		addRenderableWidget(itemButton);
		addRenderableWidget(helpButton);
		addRenderableWidget(captureModeMessage);
		addRenderableWidget(captureModeSprite);
		
		wlc.bridge.activateKeyboard();
	}
	
	private void onGrabPressed(Button button) {
		if(focused == null) return;
		
		wlc.pointerGrabs.startExclusive(new WindowGrab(wlc.getOrCreateDisplay(focused), 0));
		this.onClose();
	}
	
	private void onHidePressed(Button button) {
		if(focused == null) return;
		
		wlc.displays.removeIf((w) -> w.window == focused);
	}
	
	private void onResizePressed(Button button) {
		if(focused == null || focused.fullscreen) return;
		
		wlc.bridge.sendMotionOutside();
		GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
		
		resizeMode = true;
		resizeToplevel = focused;
		resizeWidth = focused.geometry.width();
		resizeHeight = focused.geometry.height();
		resizeLastX = resizeLastY = Double.NaN;
	}
	
	private void onOverviewPressed(Button button) {
		overviewMode = !overviewMode;

		if(overviewMode) {
			// Don't leave a half-finished click grab behind when the view changes
			implicitGrab = null;
			wlc.bridge.sendMotionOutside();
		}
	}

	/** Resize the focused window so it fills the whole desktop area. */
	private void onFillPressed(Button button) {
		if(focused == null || focused.fullscreen) return;

		// The client may render its buffer at a different scale than its
		// logical size (HiDPI). Ask for a logical size whose buffer covers
		// the whole area instead of overshooting.
		int surfaceScale = 1;
		if(focused.framebuffer != null && focused.geometry.width() > 0) {
			surfaceScale = Math.max(1, Math.round((float) focused.framebuffer.getWidth() / focused.geometry.width()));
		}

		int targetWidth = (areaWidth * guiScale) / surfaceScale;
		int targetHeight = (areaHeight * guiScale) / surfaceScale;

		wlc.bridge.resizeToplevel(focused, targetWidth, targetHeight);
	}

	private void onPinPressed(Button button) {
		if(focused == null) return;
		
		if(wlc.pinnedToplevel != focused) wlc.pinnedToplevel = focused;
		else wlc.pinnedToplevel = null;
	}
	
	private void onItemPressed(Button button) {
		if(focused == null) return;
		wlc.itemManager.giveItem(focused);
	}
	
	private void onHelpPressed(Button button) {
		if(resizeMode) return;
		String message = """
				You can see your windows here.
				Use ALT-Q to enable capture mode. It allows you to press escape \
				in the windows without closing the screen. When active it also \
				makes fullscreen windows properly take up the whole screen, \
				disabling all of the other UI elements.
				""";
		minecraft.setScreen(new PopupScreen.Builder(this, Component.literal("Window Manager Help"))
				.addMessage(Component.literal(message))
				.addButton(Component.literal("Done"), (popup) -> popup.onClose())
				.build());
	}
	
	private void exitResizeMode() {
		if(resizeToplevel != null && resizeToplevel.isAlive()) wlc.bridge.resizeToplevel(resizeToplevel, resizeWidth, resizeHeight);
		
		long window = Minecraft.getInstance().getWindow().handle();
		GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
		
		/* <HACK> */
		/* The following code makes the game remember at what position the cursor is after it was moved in disabled mode during resize */
		double mouseX[] = new double[1];
		double mouseY[] = new double[1];
		GLFW.glfwGetCursorPos(window, mouseX, mouseY);
		
		MouseHandler mouseHandler = Minecraft.getInstance().mouseHandler;
		mouseHandler.setIgnoreFirstMove(); // don't accumulate any movement in accumulatedDX,DY
		((IMouseHandlerMixin) mouseHandler).invokeOnMove(window, mouseX[0], mouseY[0]);
		/* </HACK> */
		
		resizeMode = false;
		resizeToplevel = null;
	}
	
	@Override
	public boolean isPauseScreen() {
		return false;
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int i, int j, float f) {
		super.extractBlurredBackground(context);
		
		context.outline(leftMargin - 1, topMargin - 1, areaWidth + 2, areaHeight + 2, Color.white.getRGB());
		
		guiScale = (int) Minecraft.getInstance().getWindow().getGuiScale();
		wlc.bridge.setOutputBounds(areaWidth * guiScale, areaHeight * guiScale);
		
		WLCToplevel[] toplevels = wlc.bridge.getMappedToplevels();
		selector.setEntries(toplevels);
		
		if(resizeMode && !resizeToplevel.isAlive()) {
			exitResizeMode();
		}
		
		WLCToplevel renderToplevel = null;
		lastFocused = focused;
		
		if(!resizeMode) {
			// Update focus to toplevel that has highest focus priority
			focused = wlc.bridge.getMostRecentFocus();
			wlc.bridge.focusSurface(focused);
			
			// Update selected toplevel in selector to currently focused toplevel, only if it changed
			if(selector.selection() == null || focused != lastFocused) {
				selector.select(focused);
			}
			
			// When the selection has changed, change the currently focused toplevel
			if(selector.selection() != focused) {
				focused = selector.selection();
				wlc.bridge.focusSurface(focused);
			}
			
			renderToplevel = focused;
		}
		else {
			focused = null;
			renderToplevel = resizeToplevel;
			
			wlc.bridge.focusSurface(null);
			setFocused(null); // Unfocus any widgets too
		}
		
		windows.clear();
		
		float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
		Matrix3x2fStack poseStack = context.pose();
		poseStack.pushMatrix();
		poseStack.scale(1 / guiScale, 1 / guiScale);

		if(overviewMode) {
			renderOverview(context);
		}
		else if(renderToplevel != null) {
			prepareToplevel(renderToplevel);
			
			for(WindowElement element : windows) {
				WindowFramebuffer buf = element.window.framebuffer;
				if(buf == null) continue;
				
				int x = (int) element.x - buf.getXOff();
				int y = (int) element.y - buf.getYOff();
				int w = buf.getWidth();
				int h = buf.getHeight();
				
				RenderUtils.renderFramebuffer2D(context, buf, x, y, w, h);
			}
		}
		
		poseStack.popMatrix();

		if(overviewMode) {
			drawOverviewLabels(context);
		}
		else {
			drawClock(context);
		}

		buttons.forEach((b) -> b.setFocused(false));
		
		if(focused != null) {
			grabButton.active = true;
			resizeButton.active = true;
			hideButton.active = wlc.hasDisplayFor(focused);
			pinButton.active = true;
			itemButton.active = true;
		}
		else {
			grabButton.active = false;
			resizeButton.active = false;
			hideButton.active = false;
			pinButton.active = false;
			itemButton.active = false;
		}
		
		buttons.forEach((b) -> b.visible = true);
		selector.visible = true;
		
		boolean fullscreenWindowActive = focused != null && focused.fullscreen;
		captureModeMessage.visible = false;
		captureModeSprite.visible = false;
		
		if(fullscreenWindowActive && captureModeEnabled) {
			buttons.forEach((b) -> b.visible = false);
			selector.visible = false;
		}
		else if(captureModeEnabled) {
			captureModeMessage.visible = true;
			captureModeSprite.visible = true;
		}
		
		super.extractRenderState(context, i, j, f);
	}
	
	@Override
	public void extractBackground(GuiGraphicsExtractor guiGraphics, int i, int j, float f) {
	}
	
	private HoveredSurface surfaceUnderPointer(double x, double y) {
		for(int i = windows.size() - 1; i >= 0; i--) {
			WindowElement element = windows.get(i);
			
			float sx = (float) x - element.x;
			float sy = (float) y - element.y;
			
			for(WLCSurface surface = element.window.getSurfaceTreeLast(); surface != null; surface = surface.getPrevChild()) {
				float rx = sx - surface.xSubpos;
				float ry = sy - surface.ySubpos;
				
				int width = surface.width();
				int height = surface.height();
				
				if(rx < 0 || ry < 0 || rx > width || ry > height) {
					continue;
				}
				
				if(!surface.isAlive()) continue;
				
				if(wlc.bridge.inputRegionContains(surface, rx, ry)) {
					return new HoveredSurface(surface, rx, ry);
				}
			}
		}
		
		return null;
	}
	
	@Override
	public void mouseMoved(double x, double y) {
		Size bounds = wlc.bridge.getOutputBounds();
		
		x *= guiScale;
		y *= guiScale;
		
		if(overviewMode) return;

		if(resizeMode) {
			wlc.bridge.sendMotionOutside();
			
			if(!resizeToplevel.isAlive()) {
				exitResizeMode();
				return;
			}
			
			if(Double.isNaN(resizeLastX) || Double.isNaN(resizeLastY)) {
				resizeLastX = x;
				resizeLastY = y;
			}
			
			int dx = (int) (x - resizeLastX) / 2;
			int dy = (int) (y - resizeLastY) / 2;
			resizeLastX = x;
			resizeLastY = y;
			
			resizeWidth += dx;
			resizeHeight += dy;
			
			resizeWidth = Math.clamp(resizeWidth, 0, bounds.width());
			resizeHeight = Math.clamp(resizeHeight, 0, bounds.height());
			
//			WaylandCraft.LOGGER.info("RESIZE " + resizeWidth + ", " + resizeHeight + " [" + resizeInitialWidth + ", " + resizeInitialHeight + "]");
			wlc.bridge.resizeToplevelInteractive(resizeToplevel, resizeWidth, resizeHeight);
			
			return;
		}
		
		HoveredSurface hovered = surfaceUnderPointer(x, y);
		
		if(implicitGrab != null && !implicitGrab.surface.isAlive()) implicitGrab = null;
		
		if(implicitGrab == null) {
			if(hovered != null) wlc.bridge.sendMotionRefocus(hovered.surface, hovered.rx, hovered.ry);
			else wlc.bridge.sendMotionOutside();
		}
		else {
			for(WindowElement elem : windows) {
				WLCSurface surface;
				for(surface = elem.window.getSurfaceTree(); surface != null && surface != implicitGrab.surface; surface = surface.getNextChild()) {}
				if(surface == implicitGrab.surface) {
					// Surface was found in this window elements' surface tree
					
					float rx = (float) x - elem.x - surface.xSubpos;
					float ry = (float) y - elem.y - surface.ySubpos;
					
					wlc.bridge.sendMotion(rx, ry);
					break;
				}
			}
		}
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if(resizeMode) return true;

		if(overviewMode) {
			if(super.mouseClicked(event, doubleClick)) return true;

			double gx = event.x() * guiScale;
			double gy = event.y() * guiScale;

			for(OverviewCell cell : overviewCells) {
				if(gx >= cell.x() && gx < cell.x() + cell.width() && gy >= cell.y() && gy < cell.y() + cell.height()) {
					overviewMode = false;
					focused = cell.toplevel();
					selector.select(cell.toplevel());
					wlc.bridge.focusSurface(cell.toplevel());
					return true;
				}
			}

			return false;
		}

		if(super.mouseClicked(event, doubleClick)) return true;
		
		double x = event.x() * guiScale;
		double y = event.y() * guiScale;
		
		HoveredSurface hovered = surfaceUnderPointer(x, y);
		if(implicitGrab == null && hovered != null) {
			implicitGrab = new ImplicitGrab(hovered.surface);
		}
		
		if(implicitGrab != null && !implicitGrab.pressedMouseButtons.contains(event.button())) {
			implicitGrab.pressedMouseButtons.add(event.button());
			wlc.bridge.sendButton(0x110 + event.button(), 1);
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if(resizeMode) {
			exitResizeMode();
			return true;
		}
		
		if(super.mouseReleased(event)) return true;
		
		if(implicitGrab != null && implicitGrab.pressedMouseButtons.contains(event.button())) {
			implicitGrab.pressedMouseButtons.remove(event.button());
			wlc.bridge.sendButton(0x110 + event.button(), 0);
			
			if(implicitGrab.pressedMouseButtons.isEmpty()) implicitGrab = null;
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean keyPressed(KeyEvent event) {
		if(overviewMode) {
			if(event.key() == GLFW.GLFW_KEY_ESCAPE) overviewMode = false;
			return true;
		}

		if(event.key() == GLFW.GLFW_KEY_ESCAPE && !captureModeEnabled) {
			this.onClose();
			return true;
		}
		
		if(event.key() == GLFW.GLFW_KEY_Q && event.modifiers() == GLFW.GLFW_MOD_ALT) {
			captureModeEnabled = !captureModeEnabled;
			return true;
		}
		
		if(resizeMode) return true;
		
		// Forward key press to currently focused widget
		if(getFocused() != null && getFocused().keyPressed(event)) return true;
		
		// Forward key press to current window
		if(focused != null) {
			int scancode = WaylandCraft.correctScancode(event.scancode());
			wlc.bridge.pressKey(scancode);
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean keyReleased(KeyEvent event) {
		if(resizeMode) return true;
		
		if(super.keyReleased(event)) return true;
		
		if(focused != null) {
			int scancode = WaylandCraft.correctScancode(event.scancode());
			wlc.bridge.releaseKey(scancode);
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if(resizeMode) return true;
		if(overviewMode) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

		if(super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
		
		mouseX *= guiScale;
		mouseY *= guiScale;
		
		HoveredSurface hovered = surfaceUnderPointer(mouseX, mouseY);
		
		if(hovered != null) {
			wlc.bridge.sendScroll(0, -scrollY);
			wlc.bridge.sendScroll(1, -scrollX);
			return true;
		}
		
		return false;
	}
	
	@Override
	public void removed() {
		overviewMode = false;
		if(resizeMode) exitResizeMode();
		if(implicitGrab != null) {
			implicitGrab.pressedMouseButtons.forEach((button) -> wlc.bridge.sendButton(0x110 + button, 0));
			implicitGrab = null;
		}
		wlc.bridge.deactivateKeyboard();
	}
	
	private String titleOf(WLCToplevel toplevel) {
		return Optional.ofNullable(toplevel.title).or(() -> Optional.ofNullable(toplevel.appID)).orElse("Window");
	}

	/**
	 * Draw every open window as a thumbnail on a grid, like a desktop overview.
	 * Runs inside the 1/guiScale transform, so all coordinates are game pixels.
	 */
	private void renderOverview(GuiGraphicsExtractor context) {
		WLCToplevel[] toplevels = wlc.bridge.getMappedToplevels();

		int gs = Math.max(1, guiScale);
		int areaX = leftMargin * gs;
		int areaY = topMargin * gs;
		int areaW = areaWidth * gs;
		int areaH = areaHeight * gs;

		overviewCells.clear();
		context.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0xC0101014);

		if(toplevels.length == 0) return;

		int count = toplevels.length;
		int cols = (int) Math.ceil(Math.sqrt(count));
		int rows = (int) Math.ceil(count / (double) cols);
		int gutter = Math.max(4, gs * 2);
		int cellW = Math.max(48, (areaW - gutter * (cols + 1)) / cols);
		int cellH = Math.max(32, (areaH - gutter * (rows + 1)) / rows);
		int labelSpace = 14 * gs;

		for(int i = 0; i < count; i++) {
			WLCToplevel toplevel = toplevels[i];
			int col = i % cols;
			int row = i / cols;
			int cellX = areaX + gutter + col * (cellW + gutter);
			int cellY = areaY + gutter + row * (cellH + gutter);
			boolean selected = toplevel == focused;

			context.fill(cellX, cellY, cellX + cellW, cellY + cellH, selected ? 0x80303A46 : 0x601C2027);

			WindowFramebuffer buf = toplevel.framebuffer;
			if(buf != null && buf.isValid()) {
				int bw = Math.max(1, buf.getWidth());
				int bh = Math.max(1, buf.getHeight());
				float scale = Math.min((float) (cellW - 8 * gs) / bw, (float) (cellH - labelSpace - 8 * gs) / bh);
				scale = Math.max(0.01f, scale);

				int dw = Math.max(1, (int) (bw * scale));
				int dh = Math.max(1, (int) (bh * scale));
				int dx = cellX + (cellW - dw) / 2;
				int dy = cellY + (cellH - labelSpace - dh) / 2;

				RenderUtils.renderFramebuffer2D(context, buf, dx, dy, dw, dh);
			}

			int borderColor = selected ? 0xFFFFFFFF : 0x40FFFFFF;
			context.fill(cellX - 1, cellY - 1, cellX + cellW + 1, cellY, borderColor);
			context.fill(cellX - 1, cellY + cellH, cellX + cellW + 1, cellY + cellH + 1, borderColor);
			context.fill(cellX - 1, cellY, cellX, cellY + cellH, borderColor);
			context.fill(cellX + cellW, cellY, cellX + cellW + 1, cellY + cellH, borderColor);

			overviewCells.add(new OverviewCell(toplevel, cellX, cellY, cellW, cellH));
		}
	}

	private void drawOverviewLabels(GuiGraphicsExtractor context) {
		for(OverviewCell cell : overviewCells) {
			int x = cell.x() / guiScale + 4;
			int y = (cell.y() + cell.height()) / guiScale - 11;
			int maxWidth = cell.width() / guiScale - 8;

			String title = titleOf(cell.toplevel());
			if(font.width(title) > maxWidth) {
				while(!title.isEmpty() && font.width(title + "...") > maxWidth) {
					title = title.substring(0, title.length() - 1);
				}
				title += "...";
			}
			context.text(font, Component.literal(title), x, y, 0xFFFFFFFF);
		}
	}

	private void drawClock(GuiGraphicsExtractor context) {
		String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
		int x = leftMargin + areaWidth - font.width(time) - 6;
		int y = topMargin + areaHeight - 12;
		context.text(font, Component.literal(time), x, y, 0xB0FFFFFF);
	}

	private record OverviewCell(WLCToplevel toplevel, int x, int y, int width, int height) {}

	private void prepareToplevel(WLCToplevel toplevel) {
		float x;
		float y;
		
		if(!toplevel.fullscreen || !captureModeEnabled) {
			x = leftMargin * guiScale + Math.max(0, areaWidth * guiScale / 2 - toplevel.geometry.width() / 2);
			y = topMargin * guiScale + Math.max(0, areaHeight * guiScale / 2 - toplevel.geometry.height() / 2);
		}
		else {
			x = 0;
			y = 0;
		}
		
		x -= toplevel.geometry.x();
		y -= toplevel.geometry.y();
		
		windows.add(new WindowElement(toplevel, x, y));
		
		WindowTree tree = WindowTree.constructTree(wlc.bridge, toplevel);
		preparePopupTree(tree, x, y);
	}
	
	private void preparePopupTree(WindowTree tree, float x, float y) {
		if(tree.window instanceof WLCPopup) {
			WLCPopup popup = (WLCPopup) tree.window;
			
			x += popup.getParent().geometry.x();
			y += popup.getParent().geometry.y();
			
			x += popup.offsetX;
			y += popup.offsetY;
			
			x -= popup.geometry.x();
			y -= popup.geometry.y();
			
			windows.add(new WindowElement(popup, x, y));
		}
		
		for(WindowTree child : tree.children) {
			preparePopupTree(child, x, y);
		}
	}
	
	public static class WindowElement {
		
		public WLCAbstractWindow window;
		public float x;
		public float y;
		
		public WindowElement(WLCAbstractWindow window, float x, float y) {
			this.window = window;
			this.x = x;
			this.y = y;
		}
		
	}
	
	public static class WindowTree {
		
		public WLCAbstractWindow window;
		public ArrayList<WindowTree> children;
		
		private WindowTree(WLCAbstractWindow window) {
			this.window = window;
			this.children = new ArrayList<WindowTree>();
		}
		
		public static WindowTree constructTree(WaylandCraftBridge bridge, WLCToplevel toplevel) {
			WindowTree tree = new WindowTree(toplevel);
			
			for(WLCPopup popup : bridge.getMappedPopups()) {
				WLCAbstractWindow root;
				for(root = popup; !(root instanceof WLCToplevel); root = ((WLCPopup) root).getParent()) {}
				if(root != toplevel) continue;
				addRecursive(tree, popup);
			}
			
			return tree;
		}
		
		private static WindowTree addRecursive(WindowTree tree, WLCPopup popup) {
			WLCAbstractWindow parentWindow = popup.getParent();
			WindowTree parent;
			if(parentWindow instanceof WLCPopup) {
				parent = addRecursive(tree, (WLCPopup) parentWindow);
			}
			else {
				parent = tree;
			}
			
			for(WindowTree child : parent.children) {
				if(child.window == popup) return child;
			}
			
			WindowTree child = new WindowTree(popup);
			parent.children.add(child);
			return child;
		}
		
	}
	
	private static record HoveredSurface(WLCSurface surface, float rx, float ry) {}
	
	private static class ImplicitGrab {
		
		public final WLCSurface surface;
		public HashSet<Integer> pressedMouseButtons = new HashSet<Integer>();
		
		public ImplicitGrab(WLCSurface surface) {
			this.surface = surface;
		}
		
	}
	
}
