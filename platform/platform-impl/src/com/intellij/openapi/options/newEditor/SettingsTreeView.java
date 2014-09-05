/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.options.newEditor;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.options.*;
import com.intellij.openapi.options.ex.ConfigurableWrapper;
import com.intellij.openapi.options.ex.NodeConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.*;
import com.intellij.ui.treeStructure.CachingSimpleNode;
import com.intellij.ui.treeStructure.SimpleNode;
import com.intellij.ui.treeStructure.SimpleTree;
import com.intellij.ui.treeStructure.SimpleTreeStructure;
import com.intellij.ui.treeStructure.filtered.FilteringTreeBuilder;
import com.intellij.ui.treeStructure.filtered.FilteringTreeStructure;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ui.ButtonlessScrollBarUI;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import com.intellij.util.ui.tree.WideSelectionTreeUI;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.plaf.TreeUI;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * @author Sergey.Malenkov
 */
final class SettingsTreeView extends JComponent implements Disposable, OptionsEditorColleague {
  private static final Color NORMAL_NODE = new JBColor(Gray._0, Gray._140);
  private static final Color WRONG_CONTENT = JBColor.RED;
  private static final Color MODIFIED_CONTENT = JBColor.BLUE;

  final SimpleTree myTree;
  final FilteringTreeBuilder myBuilder;

  private final SettingsFilter myFilter;
  private final MyRoot myRoot;
  private final JScrollPane myScroller;
  private JLabel mySeparator;
  private final MyRenderer myRenderer = new MyRenderer();
  private final IdentityHashMap<Configurable, MyNode> myConfigurableToNodeMap = new IdentityHashMap<Configurable, MyNode>();
  private final IdentityHashMap<UnnamedConfigurable, ConfigurableWrapper> myConfigurableToWrapperMap
    = new IdentityHashMap<UnnamedConfigurable, ConfigurableWrapper>();
  private final MergingUpdateQueue myQueue = new MergingUpdateQueue("SettingsTreeView", 150, false, this, this, this)
    .setRestartTimerOnAdd(true);

  private Configurable myQueuedConfigurable;

  SettingsTreeView(SettingsFilter filter, ConfigurableGroup... groups) {
    myFilter = filter;
    myRoot = new MyRoot(groups);
    myTree = new MyTree();
    myTree.putClientProperty(WideSelectionTreeUI.TREE_TABLE_TREE_KEY, Boolean.TRUE);
    myTree.setBackground(UIUtil.getSidePanelColor());
    myTree.getInputMap().clear();
    TreeUtil.installActions(myTree);

    myTree.setOpaque(true);
    myTree.setBorder(BorderFactory.createEmptyBorder(0, 1, 0, 0));

    myTree.setRowHeight(-1);
    myTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

    myTree.setCellRenderer(myRenderer);
    myTree.setRootVisible(false);
    myTree.setShowsRootHandles(false);

    myScroller = ScrollPaneFactory.createScrollPane(myTree, true);
    myScroller.getVerticalScrollBar().setUI(ButtonlessScrollBarUI.createTransparent());
    myScroller.setBackground(UIUtil.getSidePanelColor());
    myScroller.getViewport().setBackground(UIUtil.getSidePanelColor());
    myScroller.getVerticalScrollBar().setBackground(UIUtil.getSidePanelColor());
    add(myScroller);

    myTree.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        myBuilder.revalidateTree();
      }

      @Override
      public void componentMoved(ComponentEvent e) {
        myBuilder.revalidateTree();
      }

      @Override
      public void componentShown(ComponentEvent e) {
        myBuilder.revalidateTree();
      }
    });

    myTree.getSelectionModel().addTreeSelectionListener(new TreeSelectionListener() {
      public void valueChanged(TreeSelectionEvent event) {
        MyNode node = extractNode(event.getNewLeadSelectionPath());
        select(node == null ? null : node.myConfigurable);
      }
    });

    myBuilder = new MyBuilder(new SimpleTreeStructure.Impl(myRoot));
    myBuilder.setFilteringMerge(300, null);
    Disposer.register(this, myBuilder);
  }

  @NotNull
  String[] getPathNames(Configurable configurable) {
    ArrayDeque<String> path = new ArrayDeque<String>();
    MyNode node = findNode(configurable);
    while (node != null) {
      path.push(node.myDisplayName);
      SimpleNode parent = node.getParent();
      node = parent instanceof MyNode
             ? (MyNode)parent
             : null;
    }
    return ArrayUtil.toStringArray(path);
  }

  static Configurable getConfigurable(SimpleNode node) {
    return node instanceof MyNode
           ? ((MyNode)node).myConfigurable
           : null;
  }

  @Nullable
  MyNode findNode(Configurable configurable) {
    ConfigurableWrapper wrapper = myConfigurableToWrapperMap.get(configurable);
    return myConfigurableToNodeMap.get(wrapper != null ? wrapper : configurable);
  }

  @Nullable
  SearchableConfigurable findConfigurableById(@NotNull String id) {
    for (Configurable configurable : myConfigurableToNodeMap.keySet()) {
      if (configurable instanceof SearchableConfigurable) {
        SearchableConfigurable searchable = (SearchableConfigurable)configurable;
        if (id.equals(searchable.getId())) {
          return searchable;
        }
      }
    }
    return null;
  }

  @Nullable
  <T extends UnnamedConfigurable> T findConfigurable(@NotNull Class<T> type) {
    for (UnnamedConfigurable configurable : myConfigurableToNodeMap.keySet()) {
      if (configurable instanceof ConfigurableWrapper) {
        ConfigurableWrapper wrapper = (ConfigurableWrapper)configurable;
        configurable = wrapper.getConfigurable();
        myConfigurableToWrapperMap.put(configurable, wrapper);
      }
      if (type.isInstance(configurable)) {
        return type.cast(configurable);
      }
    }
    return null;
  }

  @Nullable
  Project findConfigurableProject(@Nullable Configurable configurable) {
    if (configurable instanceof ConfigurableWrapper) {
      ConfigurableWrapper wrapper = (ConfigurableWrapper)configurable;
      return wrapper.getExtensionPoint().getProject();
    }
    return findConfigurableProject(findNode(configurable));
  }

  @Nullable
  private static Project findConfigurableProject(@Nullable MyNode node) {
    if (node != null) {
      Configurable configurable = node.myConfigurable;
      if (configurable instanceof ConfigurableWrapper) {
        ConfigurableWrapper wrapper = (ConfigurableWrapper)configurable;
        return wrapper.getExtensionPoint().getProject();
      }
      SimpleNode parent = node.getParent();
      if (parent instanceof MyNode) {
        return findConfigurableProject((MyNode)parent);
      }
    }
    return null;
  }

  @Nullable
  private ConfigurableGroup findConfigurableGroupAt(int x, int y) {
    TreePath path = myTree.getClosestPathForLocation(x - myTree.getX(), y - myTree.getY());
    while (path != null) {
      MyNode node = extractNode(path);
      if (node == null) {
        return null;
      }
      if (node.myComposite instanceof ConfigurableGroup) {
        return (ConfigurableGroup)node.myComposite;
      }
      path = path.getParentPath();
    }
    return null;
  }

  @Nullable
  private static MyNode extractNode(@Nullable Object object) {
    if (object instanceof TreePath) {
      TreePath path = (TreePath)object;
      object = path.getLastPathComponent();
    }
    if (object instanceof DefaultMutableTreeNode) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode)object;
      object = node.getUserObject();
    }
    if (object instanceof FilteringTreeStructure.FilteringNode) {
      FilteringTreeStructure.FilteringNode node = (FilteringTreeStructure.FilteringNode)object;
      object = node.getDelegate();
    }
    return object instanceof MyNode
           ? (MyNode)object
           : null;
  }

  @Override
  public void doLayout() {
    myScroller.setBounds(0, 0, getWidth(), getHeight());
  }

  @Override
  public void paint(Graphics g) {
    super.paint(g);

    if (0 == myTree.getY()) {
      return; // separator is not needed without scrolling
    }
    if (mySeparator == null) {
      mySeparator = new JLabel();
      mySeparator.setForeground(NORMAL_NODE);
      mySeparator.setFont(UIUtil.getLabelFont());
      mySeparator.setFont(getFont().deriveFont(Font.BOLD));
    }
    int height = mySeparator.getPreferredSize().height;
    ConfigurableGroup group = findConfigurableGroupAt(0, height);
    if (group != null && group == findConfigurableGroupAt(0, -myRenderer.getSeparatorHeight())) {
      mySeparator.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 0));
      mySeparator.setText(group.getDisplayName());

      Rectangle bounds = myScroller.getViewport().getBounds();
      if (bounds.height > height) {
        bounds.height = height;
      }
      g.setColor(myTree.getBackground());
      g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
      if (g instanceof Graphics2D) {
        int h = 4; // gradient height
        int y = bounds.y + bounds.height;
        ((Graphics2D)g).setPaint(UIUtil.getGradientPaint(
          0, y, g.getColor(),
          0, y + h, ColorUtil.toAlpha(g.getColor(), 0)));
        g.fillRect(bounds.x, y, bounds.width, h);
      }
      mySeparator.setSize(bounds.width - 1, bounds.height);
      mySeparator.paint(g.create(bounds.x + 1, bounds.y, bounds.width - 1, bounds.height));
    }
  }

  void selectFirst() {
    for (ConfigurableGroup eachGroup : myRoot.myGroups) {
      Configurable[] kids = eachGroup.getConfigurables();
      if (kids.length > 0) {
        select(kids[0]);
        return;
      }
    }
  }

  ActionCallback select(@Nullable final Configurable configurable) {
    if (myBuilder.isSelectionBeingAdjusted()) {
      return new ActionCallback.Rejected();
    }
    final ActionCallback callback = new ActionCallback();
    myQueuedConfigurable = configurable;
    myQueue.queue(new Update(this) {
      public void run() {
        if (configurable == myQueuedConfigurable) {
          if (configurable == null) {
            fireSelected(null, callback);
          }
          else {
            myBuilder.getReady(this).doWhenDone(new Runnable() {
              @Override
              public void run() {
                if (configurable != myQueuedConfigurable) return;

                MyNode editorNode = findNode(configurable);
                FilteringTreeStructure.FilteringNode editorUiNode = myBuilder.getVisibleNodeFor(editorNode);
                if (editorUiNode == null) return;

                if (!myBuilder.getSelectedElements().contains(editorUiNode)) {
                  myBuilder.select(editorUiNode, new Runnable() {
                    public void run() {
                      fireSelected(configurable, callback);
                    }
                  });
                }
                else {
                  myBuilder.scrollSelectionToVisible(new Runnable() {
                    public void run() {
                      fireSelected(configurable, callback);
                    }
                  }, false);
                }
              }
            });
          }
        }
      }

      @Override
      public void setRejected() {
        super.setRejected();
        callback.setRejected();
      }
    });
    return callback;
  }

  private void fireSelected(Configurable configurable, ActionCallback callback) {
    ConfigurableWrapper wrapper = myConfigurableToWrapperMap.get(configurable);
    myFilter.myContext.fireSelected(wrapper != null ? wrapper : configurable, this).doWhenProcessed(callback.createSetDoneRunnable());
  }

  @Override
  public void dispose() {
    myQueuedConfigurable = null;
  }

  @Override
  public ActionCallback onSelected(@Nullable Configurable configurable, Configurable oldConfigurable) {
    return select(configurable);
  }

  @Override
  public ActionCallback onModifiedAdded(Configurable configurable) {
    myTree.repaint();
    return new ActionCallback.Done();
  }

  @Override
  public ActionCallback onModifiedRemoved(Configurable configurable) {
    myTree.repaint();
    return new ActionCallback.Done();
  }

  @Override
  public ActionCallback onErrorsChanged() {
    return new ActionCallback.Done();
  }

  private final class MyRoot extends CachingSimpleNode {
    private final ConfigurableGroup[] myGroups;

    private MyRoot(ConfigurableGroup[] groups) {
      super(null);
      myGroups = groups;
    }

    @Override
    protected SimpleNode[] buildChildren() {
      if (myGroups == null || myGroups.length == 0) {
        return NO_CHILDREN;
      }
      SimpleNode[] result = new SimpleNode[myGroups.length];
      for (int i = 0; i < myGroups.length; i++) {
        result[i] = new MyNode(this, myGroups[i]);
      }
      return result;
    }
  }

  private final class MyNode extends CachingSimpleNode {
    private final Configurable.Composite myComposite;
    private final Configurable myConfigurable;
    private final String myDisplayName;

    private MyNode(CachingSimpleNode parent, Configurable configurable) {
      super(parent);
      myComposite = configurable instanceof Configurable.Composite ? (Configurable.Composite)configurable : null;
      myConfigurable = configurable;
      String name = configurable.getDisplayName();
      myDisplayName = name != null ? name.replace("\n", " ") : "{ " + configurable.getClass().getSimpleName() + " }";
    }

    private MyNode(CachingSimpleNode parent, ConfigurableGroup group) {
      super(parent);
      myComposite = group;
      myConfigurable = group instanceof Configurable ? (Configurable)group : null;
      String name = group.getDisplayName();
      myDisplayName = name != null ? name.replace("\n", " ") : "{ " + group.getClass().getSimpleName() + " }";
    }

    @Override
    protected SimpleNode[] buildChildren() {
      if (myConfigurable != null) {
        myConfigurableToNodeMap.put(myConfigurable, this);
      }
      if (myComposite == null) {
        return NO_CHILDREN;
      }
      Configurable[] configurables = myComposite.getConfigurables();
      if (configurables == null || configurables.length == 0) {
        return NO_CHILDREN;
      }
      SimpleNode[] result = new SimpleNode[configurables.length];
      for (int i = 0; i < configurables.length; i++) {
        result[i] = new MyNode(this, configurables[i]);
        if (myConfigurable != null) {
          myFilter.myContext.registerKid(myConfigurable, configurables[i]);
        }
      }
      return result;
    }

    @Override
    public boolean isAlwaysLeaf() {
      return myComposite == null;
    }
  }

  private final class MyRenderer extends GroupedElementsRenderer.Tree {
    private JLabel myNodeIcon;
    private JLabel myProjectIcon;

    protected JComponent createItemComponent() {
      myTextLabel = new ErrorLabel();
      return myTextLabel;
    }

    @Override
    protected void layout() {
      myNodeIcon = new JLabel(" ", SwingConstants.RIGHT);
      myProjectIcon = new JLabel(" ", SwingConstants.LEFT);
      myNodeIcon.setOpaque(false);
      myTextLabel.setOpaque(false);
      myProjectIcon.setOpaque(false);
      myRendererComponent.add(BorderLayout.CENTER, myComponent);
      myRendererComponent.add(BorderLayout.WEST, myNodeIcon);
      myRendererComponent.add(BorderLayout.EAST, myProjectIcon);
    }

    public Component getTreeCellRendererComponent(JTree tree,
                                                  Object value,
                                                  boolean selected,
                                                  boolean expanded,
                                                  boolean leaf,
                                                  int row,
                                                  boolean focused) {
      myTextLabel.setFont(UIUtil.getLabelFont());
      myRendererComponent.setBackground(selected ? UIUtil.getTreeSelectionBackground() : myTree.getBackground());

      MyNode node = extractNode(value);
      if (node == null) {
        myTextLabel.setText(value.toString());
      }
      else {
        myTextLabel.setText(node.myDisplayName);
        // show groups in bold
        if (myRoot == node.getParent()) {
          myTextLabel.setFont(myTextLabel.getFont().deriveFont(Font.BOLD));
        }
        TreePath path = tree.getPathForRow(row);
        if (path == null) {
          if (value instanceof DefaultMutableTreeNode) {
            path = new TreePath(((DefaultMutableTreeNode)value).getPath());
          }
        }
        int forcedWidth = 2000;
        if (path != null && tree.isVisible()) {
          Rectangle visibleRect = tree.getVisibleRect();

          int nestingLevel = tree.isRootVisible() ? path.getPathCount() - 1 : path.getPathCount() - 2;

          int left = UIUtil.getTreeLeftChildIndent();
          int right = UIUtil.getTreeRightChildIndent();

          Insets treeInsets = tree.getInsets();

          int indent = (left + right) * nestingLevel + (treeInsets != null ? treeInsets.left + treeInsets.right : 0);

          forcedWidth = visibleRect.width > 0 ? visibleRect.width - indent : forcedWidth;
        }
        myRendererComponent.setPrefereedWidth(forcedWidth - 4);
      }
      // update font color for modified configurables
      myTextLabel.setForeground(selected ? UIUtil.getTreeSelectionForeground() : NORMAL_NODE);
      if (!selected && node != null) {
        Configurable configurable = node.myConfigurable;
        if (configurable != null) {
          if (myFilter.myContext.getErrors().containsKey(configurable)) {
            myTextLabel.setForeground(WRONG_CONTENT);
          }
          else if (myFilter.myContext.getModified().contains(configurable)) {
            myTextLabel.setForeground(MODIFIED_CONTENT);
          }
        }
      }
      // configure project icon
      Project project = null;
      if (node != null) {
        SimpleNode parent = node.getParent();
        if (parent instanceof MyNode) {
          if (myRoot == parent.getParent()) {
            project = findConfigurableProject(node); // show icon for top-level nodes
            if (node.myConfigurable instanceof NodeConfigurable) { // special case for custom subgroups (build.tools)
              Configurable[] configurables = ((NodeConfigurable)node.myConfigurable).getConfigurables();
              if (configurables != null) { // assume that all configurables have the same project
                project = findConfigurableProject(configurables[0]);
              }
            }
          }
          else if (((MyNode)parent).myConfigurable instanceof NodeConfigurable) {
            if (((MyNode)node.getParent()).myConfigurable instanceof NodeConfigurable) {
              project = findConfigurableProject(node); // special case for custom subgroups
            }
          }
        }
      }
      if (project != null) {
        myProjectIcon.setIcon(selected
                              ? AllIcons.General.ProjectConfigurableSelected
                              : AllIcons.General.ProjectConfigurable);
        myProjectIcon.setToolTipText(OptionsBundle.message(project.isDefault()
                                                           ? "configurable.default.project.tooltip"
                                                           : "configurable.current.project.tooltip"));
        myProjectIcon.setVisible(true);
      }
      else {
        myProjectIcon.setVisible(false);
      }
      // configure node icon
      if (value instanceof DefaultMutableTreeNode) {
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode)value;
        TreePath treePath = new TreePath(treeNode.getPath());
        myNodeIcon.setIcon(myTree.getHandleIcon(treeNode, treePath));
      }
      else {
        myNodeIcon.setIcon(null);
      }
      return myRendererComponent;
    }

    int getSeparatorHeight() {
      return mySeparatorComponent.getParent() == null ? 0 : mySeparatorComponent.getPreferredSize().height;
    }

    public boolean isUnderHandle(Point point) {
      Point handlePoint = SwingUtilities.convertPoint(myRendererComponent, point, myNodeIcon);
      Rectangle bounds = myNodeIcon.getBounds();
      return bounds.x < handlePoint.x && bounds.getMaxX() >= handlePoint.x;
    }
  }

  private final class MyTree extends SimpleTree {
    @Override
    public String getToolTipText(MouseEvent event) {
      if (event != null) {
        Component component = getDeepestRendererComponentAt(event.getX(), event.getY());
        if (component instanceof JLabel) {
          JLabel label = (JLabel)component;
          if (label.getIcon() != null) {
            String text = label.getToolTipText();
            if (text != null) {
              return text;
            }
          }
        }
      }
      return super.getToolTipText(event);
    }

    @Override
    protected boolean paintNodes() {
      return false;
    }

    @Override
    protected boolean highlightSingleNode() {
      return false;
    }

    @Override
    public void setUI(TreeUI ui) {
      TreeUI actualUI = ui;
      if (!(ui instanceof MyTreeUi)) {
        actualUI = new MyTreeUi();
      }
      super.setUI(actualUI);
    }

    @Override
    protected boolean isCustomUI() {
      return true;
    }

    @Override
    protected void configureUiHelper(TreeUIHelper helper) {
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }


    @Override
    public void processKeyEvent(KeyEvent e) {
      TreePath path = myTree.getSelectionPath();
      if (path != null) {
        if (e.getKeyCode() == KeyEvent.VK_LEFT) {
          if (isExpanded(path)) {
            collapsePath(path);
            return;
          }
        }
        else if (e.getKeyCode() == KeyEvent.VK_RIGHT) {
          if (isCollapsed(path)) {
            expandPath(path);
            return;
          }
        }
      }
      super.processKeyEvent(e);
    }

    @Override
    protected void processMouseEvent(MouseEvent e) {
      MyTreeUi ui = (MyTreeUi)myTree.getUI();
      boolean toggleNow = MouseEvent.MOUSE_RELEASED == e.getID()
                          && UIUtil.isActionClick(e, MouseEvent.MOUSE_RELEASED)
                          && !ui.isToggleEvent(e);

      if (toggleNow || MouseEvent.MOUSE_PRESSED == e.getID()) {
        TreePath path = getPathForLocation(e.getX(), e.getY());
        if (path != null) {
          Rectangle bounds = getPathBounds(path);
          if (bounds != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
            boolean selected = isPathSelected(path);
            boolean expanded = isExpanded(path);
            Component comp =
              myRenderer.getTreeCellRendererComponent(this, node, selected, expanded, node.isLeaf(), getRowForPath(path), isFocusOwner());

            comp.setBounds(bounds);
            comp.validate();

            Point point = new Point(e.getX() - bounds.x, e.getY() - bounds.y);
            if (myRenderer.isUnderHandle(point)) {
              if (toggleNow) {
                ui.toggleExpandState(path);
              }
              e.consume();
              return;
            }
          }
        }
      }

      super.processMouseEvent(e);
    }

    private final class MyTreeUi extends WideSelectionTreeUI {

      @Override
      public void toggleExpandState(TreePath path) {
        super.toggleExpandState(path);
      }

      @Override
      public boolean isToggleEvent(MouseEvent event) {
        return super.isToggleEvent(event);
      }

      @Override
      protected boolean shouldPaintExpandControl(TreePath path,
                                                 int row,
                                                 boolean isExpanded,
                                                 boolean hasBeenExpanded,
                                                 boolean isLeaf) {
        return false;
      }

      @Override
      protected void paintHorizontalPartOfLeg(Graphics g,
                                              Rectangle clipBounds,
                                              Insets insets,
                                              Rectangle bounds,
                                              TreePath path,
                                              int row,
                                              boolean isExpanded,
                                              boolean hasBeenExpanded,
                                              boolean isLeaf) {

      }

      @Override
      protected void paintVerticalPartOfLeg(Graphics g, Rectangle clipBounds, Insets insets, TreePath path) {
      }

      @Override
      public void paint(Graphics g, JComponent c) {
        GraphicsUtil.setupAntialiasing(g);
        super.paint(g, c);
      }
    }
  }

  private final class MyBuilder extends FilteringTreeBuilder {

    List<Object> myToExpandOnResetFilter;
    boolean myRefilteringNow;
    boolean myWasHoldingFilter;

    public MyBuilder(SimpleTreeStructure structure) {
      super(myTree, myFilter, structure, null);
      myTree.addTreeExpansionListener(new TreeExpansionListener() {
        public void treeExpanded(TreeExpansionEvent event) {
          invalidateExpansions();
        }

        public void treeCollapsed(TreeExpansionEvent event) {
          invalidateExpansions();
        }
      });
    }

    private void invalidateExpansions() {
      if (!myRefilteringNow) {
        myToExpandOnResetFilter = null;
      }
    }

    @Override
    protected boolean isSelectable(Object object) {
      return object instanceof MyNode;
    }

    @Override
    public boolean isAutoExpandNode(NodeDescriptor nodeDescriptor) {
      return myFilter.myContext.isHoldingFilter();
    }

    @Override
    public boolean isToEnsureSelectionOnFocusGained() {
      return false;
    }

    @Override
    protected ActionCallback refilterNow(Object preferredSelection, boolean adjustSelection) {
      final List<Object> toRestore = new ArrayList<Object>();
      if (myFilter.myContext.isHoldingFilter() && !myWasHoldingFilter && myToExpandOnResetFilter == null) {
        myToExpandOnResetFilter = myBuilder.getUi().getExpandedElements();
      }
      else if (!myFilter.myContext.isHoldingFilter() && myWasHoldingFilter && myToExpandOnResetFilter != null) {
        toRestore.addAll(myToExpandOnResetFilter);
        myToExpandOnResetFilter = null;
      }

      myWasHoldingFilter = myFilter.myContext.isHoldingFilter();

      ActionCallback result = super.refilterNow(preferredSelection, adjustSelection);
      myRefilteringNow = true;
      return result.doWhenDone(new Runnable() {
        public void run() {
          myRefilteringNow = false;
          if (!myFilter.myContext.isHoldingFilter() && getSelectedElements().isEmpty()) {
            restoreExpandedState(toRestore);
          }
        }
      });
    }

    private void restoreExpandedState(List<Object> toRestore) {
      TreePath[] selected = myTree.getSelectionPaths();
      if (selected == null) {
        selected = new TreePath[0];
      }

      List<TreePath> toCollapse = new ArrayList<TreePath>();

      for (int eachRow = 0; eachRow < myTree.getRowCount(); eachRow++) {
        if (!myTree.isExpanded(eachRow)) continue;

        TreePath eachVisiblePath = myTree.getPathForRow(eachRow);
        if (eachVisiblePath == null) continue;

        Object eachElement = myBuilder.getElementFor(eachVisiblePath.getLastPathComponent());
        if (toRestore.contains(eachElement)) continue;


        for (TreePath eachSelected : selected) {
          if (!eachVisiblePath.isDescendant(eachSelected)) {
            toCollapse.add(eachVisiblePath);
          }
        }
      }

      for (TreePath each : toCollapse) {
        myTree.collapsePath(each);
      }
    }
  }
}
