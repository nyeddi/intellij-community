// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.actions;

import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.laf.darcula.DarculaLookAndFeelInfo;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.ShortcutSet;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.DimensionService;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.openapi.wm.impl.FocusManagerImpl;
import com.intellij.ui.CaptionPanel;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.ScrollingUtil;
import com.intellij.ui.WindowMoveListener;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.speedSearch.ListWithFilter;
import com.intellij.ui.speedSearch.NameFilteringListModel;
import com.intellij.ui.speedSearch.SpeedSearch;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

import static com.intellij.ui.speedSearch.SpeedSearchSupply.ENTERED_PREFIX_PROPERTY_NAME;

public class RecentLocationsAction extends AnAction {
  private static final String RECENT_LOCATIONS_ACTION_ID = "RecentLocations";
  private static final String LOCATION_SETTINGS_KEY = "recent.locations.popup";
  private static final String SHOW_RECENT_CHANGED_LOCATIONS = "SHOW_RECENT_CHANGED_LOCATIONS";
  private static final int DEFAULT_WIDTH = JBUI.scale(700);
  private static final int DEFAULT_HEIGHT = JBUI.scale(500);
  private static final int MINIMUM_WIDTH = JBUI.scale(200);
  private static final int MINIMUM_HEIGHT = JBUI.scale(100);
  private static final Color SHORTCUT_FOREGROUND_COLOR = UIUtil.getContextHelpForeground();
  public static final String SHORTCUT_HEX_COLOR = String.format("#%02x%02x%02x",
                                                                SHORTCUT_FOREGROUND_COLOR.getRed(),
                                                                SHORTCUT_FOREGROUND_COLOR.getGreen(),
                                                                SHORTCUT_FOREGROUND_COLOR.getBlue());

  static final String EMPTY_FILE_TEXT = IdeBundle.message("recent.locations.popup.empty.file.text");

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    FeatureUsageTracker.getInstance().triggerFeatureUsed(RECENT_LOCATIONS_ACTION_ID);
    Project project = getEventProject(e);
    if (project == null) {
      return;
    }

    RecentLocationsDataModel data = new RecentLocationsDataModel(project, ContainerUtil.newArrayList());
    JBList<RecentLocationItem> list = new JBList<>(JBList.createDefaultListModel(data.getPlaces(showChanged(project))));
    final JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(list,
                                                                      ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                                                                      ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

    scrollPane.setBorder(BorderFactory.createEmptyBorder());

    ListWithFilter<RecentLocationItem> listWithFilter = (ListWithFilter<RecentLocationItem>)ListWithFilter
        .wrap(list, scrollPane, getNamer(project, data), true);

    listWithFilter.setBorder(BorderFactory.createEmptyBorder());

    final SpeedSearch speedSearch = listWithFilter.getSpeedSearch();
    speedSearch.addChangeListener(
      evt -> {
        if (evt.getPropertyName().equals(ENTERED_PREFIX_PROPERTY_NAME)) {
          if (StringUtil.isEmpty(speedSearch.getFilter())) {
            data.getEditorsToRelease().forEach(editor -> clearSelectionInEditor(editor));
          }
        }
      });

    list.setCellRenderer(new RecentLocationsRenderer(project, speedSearch, data));
    list.setEmptyText(IdeBundle.message("recent.locations.popup.empty.text"));
    list.setBackground(EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground());
    ScrollingUtil.installActions(list);
    ScrollingUtil.ensureSelectionExists(list);

    JLabel title = createTitle(showChanged(project));

    ShortcutSet showChangedOnlyShortcutSet = KeymapUtil.getActiveKeymapShortcuts(RECENT_LOCATIONS_ACTION_ID);
    JBCheckBox checkBox = createCheckbox(project, showChangedOnlyShortcutSet);

    JPanel topPanel = createHeaderPanel(title, checkBox);
    JPanel mainPanel = createMainPanel(listWithFilter, topPanel);

    Color borderColor = SystemInfoRt.isMac && LafManager.getInstance().getCurrentLookAndFeel() instanceof DarculaLookAndFeelInfo
                        ? topPanel.getBackground()
                        : null;

    Ref<Boolean> navigationRef = Ref.create(false);
    JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(mainPanel, list)
      .setProject(project)
      .setCancelOnClickOutside(true)
      .setRequestFocus(true)
      .setCancelCallback(() -> {
        if (speedSearch.isHoldingFilter() && !navigationRef.get()) {
          speedSearch.reset();
          return false;
        }
        return true;
      })
      .setResizable(true)
      .setMovable(true)
      .setBorderColor(borderColor)
      .setDimensionServiceKey(project, LOCATION_SETTINGS_KEY, true)
      .setMinSize(new Dimension(DEFAULT_WIDTH, MINIMUM_HEIGHT))
      .setLocateWithinScreenBounds(false)
      .createPopup();

    DumbAwareAction.create(event -> {
      checkBox.setSelected(!checkBox.isSelected());
      updateItems(project, data, listWithFilter, title, checkBox, popup);
    }).registerCustomShortcutSet(showChangedOnlyShortcutSet, list, popup);

    checkBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        updateItems(project, data, listWithFilter, title, checkBox, popup);
      }
    });

    Disposer.register(popup, () -> {
      Dimension scrollPaneSize = calcScrollPaneSize(scrollPane, listWithFilter, topPanel, popup.getContent());
      //save scrollPane size
      DimensionService.getInstance().setSize(LOCATION_SETTINGS_KEY, scrollPaneSize, project);
    });

    Dimension scrollPaneSize = DimensionService.getInstance().getSize(LOCATION_SETTINGS_KEY, project);
    if (scrollPaneSize == null) {
      scrollPaneSize = new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    scrollPane.setMinimumSize(new Dimension(MINIMUM_WIDTH, MINIMUM_HEIGHT));

    scrollPane.setPreferredSize(scrollPaneSize);
    popup.setSize(mainPanel.getPreferredSize());

    popup.getContent().addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        scrollPane.setPreferredSize(calcScrollPaneSize(scrollPane, listWithFilter, topPanel, popup.getContent()));
        scrollPane.revalidate();
      }
    });

    popup.addListener(new JBPopupListener() {
      @Override
      public void onClosed(@NotNull LightweightWindowEvent event) {
        data.getEditorsToRelease().forEach(editor -> EditorFactory.getInstance().releaseEditor(editor));
      }
    });

    initSearchActions(project, list, popup, navigationRef);

    IdeEventQueue.getInstance().getPopupManager().closeAllPopups(false);

    list.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        if (!(e.getOppositeComponent() instanceof JCheckBox)) {
          popup.cancel();
        }
      }
    });

    showPopup(project, popup);
  }

  private static void updateItems(@NotNull Project project,
                                  @NotNull RecentLocationsDataModel data,
                                  @NotNull ListWithFilter<RecentLocationItem> listWithFilter,
                                  @NotNull JLabel title,
                                  @NotNull JBCheckBox checkBox,
                                  @NotNull JBPopup popup) {
    boolean state = checkBox.isSelected();
    PropertiesComponent.getInstance(project).setValue(SHOW_RECENT_CHANGED_LOCATIONS, state);
    updateModel(listWithFilter, data, state);
    updateTitleText(title, state);

    FocusManagerImpl.getInstance().requestFocus(listWithFilter, false);

    popup.pack(false, true);
  }

  @NotNull
  public JBCheckBox createCheckbox(@NotNull Project project, @NotNull ShortcutSet checkboxShortcutSet) {
    String text = "<html>"
                  + IdeBundle.message("recent.locations.title.text")
                  + " <font color=\"" + SHORTCUT_HEX_COLOR + "\">"
                  + KeymapUtil.getShortcutsText(checkboxShortcutSet.getShortcuts()) + "</font>"
                  + "</html>";
    JBCheckBox checkBox = new JBCheckBox(text);
    checkBox.setSelected(showChanged(project));
    checkBox.setBorder(JBUI.Borders.empty());
    checkBox.setOpaque(false);

    return checkBox;
  }

  @NotNull
  private static Dimension calcScrollPaneSize(@NotNull JScrollPane scrollPane,
                                              @NotNull ListWithFilter<RecentLocationItem> listWithFilter,
                                              @NotNull JPanel topPanel,
                                              @NotNull JComponent content) {
    Dimension contentSize = content.getSize();
    int speedSearchHeight = listWithFilter.getSize().height - scrollPane.getSize().height;
    Dimension dimension = new Dimension(contentSize.width, contentSize.height - topPanel.getSize().height - speedSearchHeight);
    JBInsets.removeFrom(dimension, content.getInsets());
    return dimension;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabled(getEventProject(e) != null);
  }

  static void clearSelectionInEditor(@NotNull Editor editor) {
    editor.getSelectionModel().removeSelection(true);
  }

  private static void showPopup(@NotNull Project project, @NotNull JBPopup popup) {
    Point savedLocation = DimensionService.getInstance().getLocation(LOCATION_SETTINGS_KEY, project);
    Window recentFocusedWindow = WindowManagerEx.getInstanceEx().getMostRecentFocusedWindow();
    if (savedLocation != null && recentFocusedWindow != null) {
      popup.showInScreenCoordinates(recentFocusedWindow, savedLocation);
    }
    else {
      popup.showCenteredInCurrentWindow(project);
    }
  }

  private static void updateModel(@NotNull ListWithFilter<RecentLocationItem> listWithFilter,
                                  @NotNull RecentLocationsDataModel data,
                                  boolean changed) {
    NameFilteringListModel<RecentLocationItem> model = (NameFilteringListModel<RecentLocationItem>)listWithFilter.getList().getModel();
    DefaultListModel<RecentLocationItem> originalModel = (DefaultListModel<RecentLocationItem>)model.getOriginalModel();

    originalModel.removeAllElements();
    data.getPlaces(changed).forEach(item -> originalModel.addElement(item));

    listWithFilter.getSpeedSearch().reset();
  }

  @NotNull
  private static JPanel createMainPanel(@NotNull ListWithFilter listWithFilter, @NotNull JPanel topPanel) {
    JPanel mainPanel = new JPanel(new VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false));
    mainPanel.add(topPanel);
    mainPanel.add(listWithFilter);
    return mainPanel;
  }

  @NotNull
  private static JPanel createHeaderPanel(@NotNull JLabel title, @NotNull JComponent checkbox) {
    JPanel topPanel = new CaptionPanel();
    topPanel.add(title, BorderLayout.WEST);
    topPanel.add(checkbox, BorderLayout.EAST);

    Dimension size = topPanel.getPreferredSize();
    size.height = JBUI.scale(29);
    topPanel.setPreferredSize(size);
    topPanel.setBorder(JBUI.Borders.empty(5, 8));

    WindowMoveListener moveListener = new WindowMoveListener(topPanel);
    topPanel.addMouseListener(moveListener);
    topPanel.addMouseMotionListener(moveListener);

    return topPanel;
  }

  @NotNull
  private static JLabel createTitle(boolean showChanged) {
    JBLabel title = new JBLabel();
    title.setFont(title.getFont().deriveFont(Font.BOLD));
    updateTitleText(title, showChanged);
    return title;
  }

  private static void updateTitleText(@NotNull JLabel title, boolean showChanged) {
    title.setText(showChanged
                  ? IdeBundle.message("recent.locations.changed.locations")
                  : IdeBundle.message("recent.locations.popup.title"));
  }

  @NotNull
  private static Function<RecentLocationItem, String> getNamer(@NotNull Project project, @NotNull RecentLocationsDataModel data) {
    return value -> {
      String breadcrumb = data.getBreadcrumbsMap(showChanged(project)).get(value.getInfo());
      EditorEx editor = value.getEditor();

      return breadcrumb + " " + value.getInfo().getFile().getName() + " " + editor.getDocument().getText();
    };
  }

  private static void initSearchActions(@NotNull Project project,
                                        @NotNull JBList<RecentLocationItem> list,
                                        @NotNull JBPopup popup,
                                        @NotNull Ref<Boolean> navigationRef) {
    list.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent event) {
        int clickCount = event.getClickCount();
        if (clickCount > 1 && clickCount % 2 == 0) {
          event.consume();
          navigateToSelected(project, list, popup, navigationRef);
        }
      }
    });

    DumbAwareAction.create(e -> navigateToSelected(project, list, popup, navigationRef))
      .registerCustomShortcutSet(CustomShortcutSet.fromString("ENTER"), list, null);
  }

  private static void navigateToSelected(@NotNull Project project,
                                         @NotNull JBList<RecentLocationItem> list,
                                         @NotNull JBPopup popup,
                                         @NotNull Ref<Boolean> navigationRef) {
    IdeDocumentHistory.getInstance(project).gotoPlaceInfo(list.getSelectedValue().getInfo());

    navigationRef.set(true);
    popup.closeOk(null);
  }

  static boolean showChanged(@NotNull Project project) {
    return PropertiesComponent.getInstance(project).getBoolean(SHOW_RECENT_CHANGED_LOCATIONS, false);
  }
}
