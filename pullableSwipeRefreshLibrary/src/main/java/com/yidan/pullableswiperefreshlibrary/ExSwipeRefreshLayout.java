package com.yidan.pullableswiperefreshlibrary;
/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.NestedScrollView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;

/**
 * The SwipeRefreshLayout should be used whenever the user can refresh the
 * contents of a view via a vertical swipe gesture. The activity that
 * instantiates this view should add an OnRefreshListener to be notified
 * whenever the swipe to refresh gesture is completed. The SwipeRefreshLayout
 * will notify the listener each and every time the gesture is completed again;
 * the listener is responsible for correctly determining when to actually
 * initiate a refresh of its content. If the listener determines there should
 * not be a refresh, it must call setRefreshing(false) to cancel any visual
 * indication of a refresh. If an activity wishes to show just the progress
 * animation, it should call setRefreshing(true). To disable the gesture and
 * progress animation, call setEnabled(false) on the view.
 * <p>
 * This layout should be made the parent of the view that will be refreshed as a
 * result of the gesture and can only support one direct child. This view will
 * also be made the target of the gesture and will be forced to match both the
 * width and the height supplied in this layout. The SwipeRefreshLayout does not
 * provide accessibility events; instead, a menu item must be provided to allow
 * refresh of the content wherever this gesture is used.
 * </p>
 */
public class ExSwipeRefreshLayout extends ViewGroup {

    private static final String LOG_TAG = ExSwipeRefreshLayout.class.getSimpleName();
    private static final int HEADER_VIEW_HEIGHT = 50;// HeaderView height (dp)

    private static final float DECELERATE_INTERPOLATION_FACTOR = 2f;
    private static final int INVALID_POINTER = -1;
    private static final float DRAG_RATE = .5f;

    private static final int SCALE_DOWN_DURATION = 150;
    private static final int ANIMATE_TO_TRIGGER_DURATION = 200;
    private static final int ANIMATE_TO_START_DURATION = 200;
    private static final int DEFAULT_CIRCLE_TARGET = 64;

    // the target of the gesture，like RecyclerView,ListView,ScrollView,GridView
    // etc. (Can be LinearLayout,RelativeLayout,FrameLayout after extension)
    private View mTarget;

    private Mode mMode = Mode.getDefault();

    private OnPullFromStartListener mOnPullFromStartListener;
    private OnPullFromEndListener mOnPullFromEndListener;

    // flag of if user started with pull from start and triggered refresh
    private boolean mIsRefreshing = false;
    // flag of if user started with pull from end and triggered load more
    private boolean mIsLoadingMore = false;

    private int mTouchSlop;
    private float mTotalDragDistance = -1;
    private int mMediumAnimationDuration;
    private int mCurrentTargetOffsetTop;
    private boolean mOriginalOffsetCalculated = false;

    private float mInitialMotionY;
    private boolean mIsBeingDragged;
    private int mActivePointerId = INVALID_POINTER;
    private boolean mScale;

    private boolean mReturningToStart;
    private final DecelerateInterpolator mDecelerateInterpolator;
    private static final int[] LAYOUT_ATTRS = new int[]{android.R.attr.enabled};

    private HeadViewContainer mHeadViewContainer;
    private RelativeLayout mFooterViewContainer;
    private int mHeaderViewIndex = -1;
    private int mFooterViewIndex = -1;

    protected int mFrom;

    private float mStartingScale;

    protected int mOriginalOffsetTop;

    private Animation mScaleAnimation;

    private Animation mScaleDownAnimation;

    private Animation mScaleDownToStartAnimation;

    private float mSpinnerFinalOffset;

    private boolean mNotify;

    private int mHeaderViewWidth;

    private int mFooterViewWidth;

    private int mHeaderViewHeight;

    private int mFooterViewHeight;

    private boolean mUsingCustomStart;

    private boolean targetScrollWithLayout = true;

    private int pushDistance = 0;

    private CircleProgressView defaultProgressView = null;

    private boolean usingDefaultHeader = true;

    private float density = 1.0f;

    private boolean isProgressEnable = true;

    public void setMode(Mode mode) {
        this.mMode = mode;
    }

    /**
     * Animation listener for pull from start to refresh
     */
    private AnimationListener mPullFromStartAnimListener = new AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
            isProgressEnable = false;
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            isProgressEnable = true;
            if (mIsRefreshing) {
                if (mNotify) {
                    if (usingDefaultHeader) {
                        ViewCompat.setAlpha(defaultProgressView, 1.0f);
                        defaultProgressView.setOnDraw(true);
                        new Thread(defaultProgressView).start();
                    }
                    if (mOnPullFromStartListener != null) {
                        mOnPullFromStartListener.onRefresh();
                    }
                }
            } else {
                mHeadViewContainer.setVisibility(View.GONE);
                if (mScale) {
                    setAnimationProgress(0);
                } else {
                    setTargetOffsetTopAndBottom(mOriginalOffsetTop
                            - mCurrentTargetOffsetTop, true);
                }
            }
            mCurrentTargetOffsetTop = mHeadViewContainer.getTop();
            updatePullRefreshListenerCallBack();
        }
    };

    private void updatePullRefreshListenerCallBack() {
        int distance = mCurrentTargetOffsetTop + mHeadViewContainer.getHeight();
        if (mOnPullFromStartListener != null) {
            mOnPullFromStartListener.onPullDistance(distance);
        }
        if (usingDefaultHeader && isProgressEnable) {
            defaultProgressView.setPullDistance(distance);
        }
    }

    /**
     * Set the pull to refresh header view
     *
     * @param child
     */
    public void setHeaderView(View child) {
        if (child == null) {
            return;
        }
        if (mHeadViewContainer == null) {
            return;
        }
        usingDefaultHeader = false;
        mHeadViewContainer.removeAllViews();
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                mHeaderViewWidth, mHeaderViewHeight);
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        mHeadViewContainer.addView(child, layoutParams);
    }

    /**
     * Set the push load more footer view
     * @param child
     */
    public void setFooterView(View child) {
        if (child == null) {
            return;
        }
        if (mFooterViewContainer == null) {
            return;
        }
        mFooterViewContainer.removeAllViews();
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                mFooterViewWidth, mFooterViewHeight);
        mFooterViewContainer.addView(child, layoutParams);
    }

    public ExSwipeRefreshLayout(Context context) {
        this(context, null);
    }

    @SuppressWarnings("deprecation")
    public ExSwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        /**
         * Distance in pixels a touch can wander before we think the user is scrolling
         */
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mMediumAnimationDuration = getResources().getInteger(
                android.R.integer.config_mediumAnimTime);

        setWillNotDraw(false);
        mDecelerateInterpolator = new DecelerateInterpolator(
                DECELERATE_INTERPOLATION_FACTOR);

        final TypedArray a = context
                .obtainStyledAttributes(attrs, LAYOUT_ATTRS);
        setEnabled(a.getBoolean(0, true));
        a.recycle();

        WindowManager wm = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        mHeaderViewWidth = (int) display.getWidth();
        mFooterViewWidth = (int) display.getWidth();
        mHeaderViewHeight = (int) (HEADER_VIEW_HEIGHT * metrics.density);
        mFooterViewHeight = (int) (HEADER_VIEW_HEIGHT * metrics.density);
        defaultProgressView = new CircleProgressView(getContext());
        createHeaderViewContainer();
        createFooterViewContainer();
        ViewCompat.setChildrenDrawingOrderEnabled(this, true);
        mSpinnerFinalOffset = DEFAULT_CIRCLE_TARGET * metrics.density;
        density = metrics.density;
        mTotalDragDistance = mSpinnerFinalOffset;
    }

    /**
     * Returns the index of the child to draw for this iteration.
     *
     * @param childCount
     * @param i
     * @return
     */
    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        if (mHeaderViewIndex < 0 && mFooterViewIndex < 0) {
            return i;
        }

        // Draw the header view and footer view last
        if (i == childCount - 2) {
            return mHeaderViewIndex;
        }
        if (i == childCount - 1) {
            return mFooterViewIndex;
        }
        int bigIndex = mFooterViewIndex > mHeaderViewIndex ? mFooterViewIndex
                : mHeaderViewIndex;
        int smallIndex = mFooterViewIndex < mHeaderViewIndex ? mFooterViewIndex
                : mHeaderViewIndex;
        // Move the children between the header view and the footer view earlier one
        if (i >= smallIndex && i < bigIndex - 1) {
            return i + 1;
        }
        // Move the children after the later of header view and the footer view earlier two
        if (i >= bigIndex || (i == bigIndex - 1)) {
            return i + 2;
        }
        // Keep the children before the header view the same
        return i;
    }

    /**
     * create header view's container
     */
    private void createHeaderViewContainer() {
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                (int) (mHeaderViewHeight * 0.8),
                (int) (mHeaderViewHeight * 0.8));
        layoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        mHeadViewContainer = new HeadViewContainer(getContext());
        mHeadViewContainer.setVisibility(View.GONE);
        defaultProgressView.setVisibility(View.VISIBLE);
        defaultProgressView.setOnDraw(false);
        mHeadViewContainer.addView(defaultProgressView, layoutParams);
        addView(mHeadViewContainer);
    }

    /**
     * create footer view's container
     */
    private void createFooterViewContainer() {
        mFooterViewContainer = new RelativeLayout(getContext());
        mFooterViewContainer.setVisibility(View.GONE);
        addView(mFooterViewContainer);
    }

    public void setHeaderViewBackgroundColor(int color) {
        mHeadViewContainer.setBackgroundColor(color);
    }

    public void setOnPullFromStartListener(OnPullFromStartListener listener) {
        mOnPullFromStartListener = listener;
    }

    public void setOnPullFromEndListener(
            OnPullFromEndListener onPullFromEndListener) {
        this.mOnPullFromEndListener = onPullFromEndListener;
    }

    /**
     * Notify the widget that refresh state has changed. Do not call this when
     * refresh is triggered by a swipe gesture.
     *
     * @param refreshing Whether or not the view should show refresh progress.
     */
    public void setRefreshing(boolean refreshing) {
        if (refreshing && mIsRefreshing != refreshing) {
            // scale and show
            mIsRefreshing = refreshing;
            int endTarget = 0;
            if (!mUsingCustomStart) {
                endTarget = (int) (mSpinnerFinalOffset + mOriginalOffsetTop);
            } else {
                endTarget = (int) mSpinnerFinalOffset;
            }
            setTargetOffsetTopAndBottom(endTarget - mCurrentTargetOffsetTop,
                    true /* requires update */);
            mNotify = false;
            startScaleUpAnimation(mPullFromStartAnimListener);
        } else {
            setRefreshing(refreshing, false /* notify */);
            if (usingDefaultHeader) {
                defaultProgressView.setOnDraw(false);
            }
        }
    }

    private void startScaleUpAnimation(AnimationListener listener) {
        mHeadViewContainer.setVisibility(View.VISIBLE);
        mScaleAnimation = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime,
                                            Transformation t) {
                setAnimationProgress(interpolatedTime);
            }
        };
        mScaleAnimation.setDuration(mMediumAnimationDuration);
        if (listener != null) {
            mHeadViewContainer.setAnimationListener(listener);
        }
        mHeadViewContainer.clearAnimation();
        mHeadViewContainer.startAnimation(mScaleAnimation);
    }

    private void setAnimationProgress(float progress) {
        if (!usingDefaultHeader) {
            progress = 1;
        }
        ViewCompat.setScaleX(mHeadViewContainer, progress);
        ViewCompat.setScaleY(mHeadViewContainer, progress);
    }

    private void setRefreshing(boolean refreshing, final boolean notify) {
        if (mIsRefreshing != refreshing) {
            mNotify = notify;
            ensureTarget();
            mIsRefreshing = refreshing;
            if (mIsRefreshing) {
                animateOffsetToCorrectPosition(mCurrentTargetOffsetTop,
                        mPullFromStartAnimListener);
            } else {
//                startScaleDownAnimation(mPullFromStartAnimListener);
                animateOffsetToStartPosition(mCurrentTargetOffsetTop, mPullFromStartAnimListener);
            }
        }
    }

    private void startScaleDownAnimation(AnimationListener listener) {
        mScaleDownAnimation = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime,
                                            Transformation t) {
                setAnimationProgress(1 - interpolatedTime);
            }
        };
        mScaleDownAnimation.setDuration(SCALE_DOWN_DURATION);
        mHeadViewContainer.setAnimationListener(listener);
        mHeadViewContainer.clearAnimation();
        mHeadViewContainer.startAnimation(mScaleDownAnimation);
    }

    public boolean isRefreshing() {
        return mIsRefreshing;
    }

    /**
     * ensure target view isn't null
     */
    private void ensureTarget() {
        if (mTarget == null) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!child.equals(mHeadViewContainer)
                        && !child.equals(mFooterViewContainer)) {
                    mTarget = child;
                    break;
                }
            }
        }
    }

    /**
     * Set the distance to trigger a sync in dips
     *
     * @param distance
     */
    public void setDistanceToTriggerSync(int distance) {
        mTotalDragDistance = distance;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right,
                            int bottom) {
        final int width = getMeasuredWidth();
        final int height = getMeasuredHeight();
        if (getChildCount() == 0) {
            return;
        }
        if (mTarget == null) {
            ensureTarget();
        }
        if (mTarget == null) {
            return;
        }
        int distance = mCurrentTargetOffsetTop + mHeadViewContainer.getMeasuredHeight();
        if (!isTargetScrollWithLayout()) {
            distance = 0;
        }

        /* update the target view's position */
        final View child = mTarget;
        final int childLeft = getPaddingLeft();
        final int childTop = getPaddingTop() + distance - pushDistance;
        final int childWidth = width - getPaddingLeft() - getPaddingRight();
        final int childHeight = height - getPaddingTop() - getPaddingBottom();
        Log.d(LOG_TAG, "debug:onLayout childHeight = " + childHeight);
        child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);

        /* update header view's position */
        int headViewWidth = mHeadViewContainer.getMeasuredWidth();
        int headViewHeight = mHeadViewContainer.getMeasuredHeight();
        mHeadViewContainer.layout((width / 2 - headViewWidth / 2),
                mCurrentTargetOffsetTop, (width / 2 + headViewWidth / 2),
                mCurrentTargetOffsetTop + headViewHeight);

        /* update footer view's position */
        int footViewWidth = mFooterViewContainer.getMeasuredWidth();
        int footViewHeight = mFooterViewContainer.getMeasuredHeight();
        mFooterViewContainer.layout((width / 2 - footViewWidth / 2), height
                - pushDistance, (width / 2 + footViewWidth / 2), height
                + footViewHeight - pushDistance);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mTarget == null) {
            ensureTarget();
        }
        if (mTarget == null) {
            return;
        }

        /* measure target view */
        mTarget.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth()
                        - getPaddingLeft() - getPaddingRight(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight()
                                - getPaddingTop() - getPaddingBottom(),
                        MeasureSpec.EXACTLY));

        /* measure header view */
        mHeadViewContainer.measure(MeasureSpec.makeMeasureSpec(
                mHeaderViewWidth, MeasureSpec.EXACTLY), MeasureSpec
                .makeMeasureSpec(3 * mHeaderViewHeight, MeasureSpec.EXACTLY));

        /* measure footer view */
        mFooterViewContainer.measure(MeasureSpec.makeMeasureSpec(
                mFooterViewWidth, MeasureSpec.EXACTLY), MeasureSpec
                .makeMeasureSpec(mFooterViewHeight, MeasureSpec.EXACTLY));

        /* on pull refresh listener's on pull distance, at the same time update pull refresh progress view */
        if (!mUsingCustomStart && !mOriginalOffsetCalculated) {
            mOriginalOffsetCalculated = true;
            mCurrentTargetOffsetTop = mOriginalOffsetTop = -mHeadViewContainer
                    .getMeasuredHeight();
            updatePullRefreshListenerCallBack();
        }

        /* update header view's index */
        mHeaderViewIndex = -1;
        for (int index = 0; index < getChildCount(); index++) {
            if (getChildAt(index) == mHeadViewContainer) {
                mHeaderViewIndex = index;
                break;
            }
        }

        /* update footer view's index */
        mFooterViewIndex = -1;
        for (int index = 0; index < getChildCount(); index++) {
            if (getChildAt(index) == mFooterViewContainer) {
                mFooterViewIndex = index;
                break;
            }
        }
    }

    private View mNestedScrollView;

    /**
     * Cet nested scroll view so you can make any scroll view nested with this extended SwipeRefreshLayout.
     * @param nestedScrollView
     */
    public void setNestedScrollView(View nestedScrollView) {
        mNestedScrollView = nestedScrollView;
    }

    /**
     * Check if the target is scrolled to very top.
     *
     * @return
     */
    public boolean isChildScrollToTop() {
        if (mTarget instanceof FrameLayout ||
                mTarget instanceof RelativeLayout ||
                mTarget instanceof LinearLayout) {
            // if we already set the nested scroll view, just check it.
            if (mNestedScrollView != null) {
                return viewScrollTopCheck(mNestedScrollView);
            }
            // if no nested scroll view, check target view's children to see if any child can be scrolled.
            View child;
            for (int i = 0; i < ((ViewGroup) mTarget).getChildCount(); i++) {
                child = ((ViewGroup) mTarget).getChildAt(i);
                if (child instanceof ScrollView || child instanceof NestedScrollView || child instanceof RecyclerView || child instanceof AbsListView) {
                    return viewScrollTopCheck(child);
                }
            }
        }

        // if target view is a scrollable view, check if it is scrolled to the very top.
        return viewScrollTopCheck(mTarget);
    }

    private boolean viewScrollTopCheck(View targetView) {
        if (Build.VERSION.SDK_INT < 14) {
            if (targetView instanceof AbsListView) {
                final AbsListView absListView = (AbsListView) targetView;
                return !(absListView.getChildCount() > 0 && (absListView
                        .getFirstVisiblePosition() > 0 || absListView
                        .getChildAt(0).getTop() < absListView.getPaddingTop()));
            } else {
                return !(targetView.getScrollY() > 0);
            }
        } else {
            return !ViewCompat.canScrollVertically(targetView, -1);
        }
    }

    /**
     * Check if the target view is scrolled to very bottom.
     *
     * @return
     */
    public boolean isChildScrollToBottom() {
        if (mTarget instanceof FrameLayout ||
                mTarget instanceof RelativeLayout ||
                mTarget instanceof LinearLayout) {
            // if we already set the nested scroll view, just check it.
            if (mNestedScrollView != null) {
                return viewScrollBottomCheck(mNestedScrollView);
            }
            // if no nested scroll view, check target view's children to see if any child can be scrolled.
            View child;
            for (int i = 0; i < ((ViewGroup) mTarget).getChildCount(); i++) {
                child = ((ViewGroup) mTarget).getChildAt(i);
                if (child instanceof ScrollView || child instanceof NestedScrollView || child instanceof RecyclerView || child instanceof AbsListView) {
                    return viewScrollBottomCheck(child);
                }
            }
        }

        // if target view is a scrollable view, check if it is scrolled to the very top.
        return viewScrollBottomCheck(mTarget);
    }

    private boolean viewScrollBottomCheck(View targetView) {
        if (targetView instanceof RecyclerView) {
            RecyclerView recyclerView = (RecyclerView) targetView;
            RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
            int count = recyclerView.getAdapter().getItemCount();
            if (layoutManager instanceof LinearLayoutManager && count > 0) {
                LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
                if (linearLayoutManager.findLastCompletelyVisibleItemPosition() == count - 1) {
                    return true;
                }
            } else if (layoutManager instanceof StaggeredGridLayoutManager) {
                StaggeredGridLayoutManager staggeredGridLayoutManager = (StaggeredGridLayoutManager) layoutManager;
                int[] lastItems = new int[2];
                staggeredGridLayoutManager
                        .findLastCompletelyVisibleItemPositions(lastItems);
                int lastItem = Math.max(lastItems[0], lastItems[1]);
                if (lastItem == count - 1) {
                    return true;
                }
            }
            return false;
        } else if (targetView instanceof AbsListView) {
            final AbsListView absListView = (AbsListView) targetView;
            int count = absListView.getAdapter().getCount();
            int firstPos = absListView.getFirstVisiblePosition();
            if (firstPos == 0
                    && absListView.getChildAt(0).getTop() >= absListView
                    .getPaddingTop()) {
                return false;
            }
            int lastPos = absListView.getLastVisiblePosition();
            if (lastPos > 0 && count > 0 && lastPos == count - 1) {
                return true;
            }
            return false;
        } else if (targetView instanceof ScrollView) {
            ScrollView scrollView = (ScrollView) targetView;
            View view = (View) scrollView
                    .getChildAt(scrollView.getChildCount() - 1);
            if (view != null) {
                int diff = (view.getBottom() - (scrollView.getHeight() + scrollView
                        .getScrollY()));
                if (diff <= 0) {
                    return true;
                }
            }
        } else if (targetView instanceof NestedScrollView) {
            NestedScrollView nestedScrollView = (NestedScrollView) targetView;
            View view = (View) nestedScrollView.getChildAt(nestedScrollView.getChildCount() - 1);
            if (view != null) {
                int diff = (view.getBottom() - (nestedScrollView.getHeight() + nestedScrollView.getScrollY()));
                if (diff <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // if true, it's pull to refresh, otherwise is push to load
    private boolean isRefreshPull;

    /**
     * Check if should intercept target view's touch event:
     * if target view or the settled nested scroll view can still scroll, don't intercept it;
     * otherwise handle touch event itself:
     *     1. if gesture is dragging down, handle pull to refresh gesture;
     *     2. if gesture is dragging up, handle push to load more gesutre;
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        ensureTarget();

        final int action = MotionEventCompat.getActionMasked(ev);

        if (mReturningToStart && action == MotionEvent.ACTION_DOWN) {
            mReturningToStart = false;
        }
        if (!isEnabled() || mReturningToStart || mIsRefreshing || mIsLoadingMore
                || (!isChildScrollToTop() && !isChildScrollToBottom())) {
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                setTargetOffsetTopAndBottom(mOriginalOffsetTop - mHeadViewContainer.getTop(), true);
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                mIsBeingDragged = false;
                final float initialMotionY = getMotionEventY(ev, mActivePointerId);
                if (initialMotionY == -1) {
                    return false;
                }
                mInitialMotionY = initialMotionY;

            case MotionEvent.ACTION_MOVE:
                if (mActivePointerId == INVALID_POINTER) {
                    Log.e(LOG_TAG,
                            "Got ACTION_MOVE event but don't have an active pointer id.");
                    return false;
                }

                final float y = getMotionEventY(ev, mActivePointerId);
                if (y == -1) {
                    return false;
                }
                float yDiff = 0;
                if (mMode == Mode.DISABLED) { // if mode set to disabled, don't intercept touch event
                    break;
                }
                yDiff = y - mInitialMotionY;

                if (yDiff < 0 && mMode == Mode.PULL_FROM_START) {
                    // if mode is PULL_FROM_START and gesture tries to pull from end, don't intercept this
                    break;
                }

                if (yDiff > 0 && mMode == Mode.PULL_FROM_END) {
                    // if mode is PULL_FROM_END and gesture tries to pull from start, don't intercept this
                    break;
                }

                // if gesture matches the mode and child scrolled to the edge, mark layout being dragged
                if ((isChildScrollToTop() && isChildScrollToBottom()) || (isChildScrollToTop() && yDiff > 0) || (isChildScrollToBottom() && yDiff < 0)) {
                    if (Math.abs(yDiff) > mTouchSlop && !mIsBeingDragged) {
                        mIsBeingDragged = true;
                        isRefreshPull = yDiff > 0;
                    }
                }
                break;

            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                break;
        }

        return mIsBeingDragged;// if being dragged, should intercept children's touch event
    }

    private float getMotionEventY(MotionEvent ev, int activePointerId) {
        final int index = MotionEventCompat.findPointerIndex(ev,
                activePointerId);
        if (index < 0) {
            return -1;
        }
        return MotionEventCompat.getY(ev, index);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean b) {
        // Nope.
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = MotionEventCompat.getActionMasked(ev);

        if (mReturningToStart && action == MotionEvent.ACTION_DOWN) {
            mReturningToStart = false;
        }
        if (!isEnabled() || mReturningToStart
                || (!isChildScrollToTop() && !isChildScrollToBottom())) {
            // if target view can scroll, don't handle it.
            return false;
        }

        if (!isRefreshPull) {
            return handlePushTouchEvent(ev, action);
        } else {
            return handlePullTouchEvent(ev, action);
        }
    }

    /**
     * handle the touch event while pulling to refresh.
     * @param ev
     * @param action
     * @return
     */
    private boolean handlePullTouchEvent(MotionEvent ev, int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                mIsBeingDragged = false;
                break;

            case MotionEvent.ACTION_MOVE: {
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev,
                        mActivePointerId);
                if (pointerIndex < 0) {
                    Log.e(LOG_TAG,
                            "Got ACTION_MOVE event but have an invalid active pointer id.");
                    return false;
                }

                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float overScrollTop = (y - mInitialMotionY) * DRAG_RATE;
                if (mIsBeingDragged) {
                    float originalDragPercent = overScrollTop / mTotalDragDistance;
                    if (originalDragPercent < 0) {
                        return false;
                    }
                    float dragPercent = Math.min(1f, Math.abs(originalDragPercent));
                    float extraOS = Math.abs(overScrollTop) - mTotalDragDistance;
                    float slingshotDist = mUsingCustomStart ? mSpinnerFinalOffset
                            - mOriginalOffsetTop : mSpinnerFinalOffset;
                    float tensionSlingshotPercent = Math.max(0,
                            Math.min(extraOS, slingshotDist * 2) / slingshotDist);
                    float tensionPercent = (float) ((tensionSlingshotPercent / 4) - Math
                            .pow((tensionSlingshotPercent / 4), 2)) * 2f;
                    float extraMove = (slingshotDist) * tensionPercent * 2;

                    int targetY = mOriginalOffsetTop
                            + (int) ((slingshotDist * dragPercent) + extraMove);
                    if (mHeadViewContainer.getVisibility() != View.VISIBLE) {
                        mHeadViewContainer.setVisibility(View.VISIBLE);
                    }
                    if (!mScale) {
                        ViewCompat.setScaleX(mHeadViewContainer, 1f);
                        ViewCompat.setScaleY(mHeadViewContainer, 1f);
                    }
                    if (usingDefaultHeader) {
                        float alpha = overScrollTop / mTotalDragDistance;
                        if (alpha >= 1.0f) {
                            alpha = 1.0f;
                        }
                        ViewCompat.setScaleX(defaultProgressView, alpha);
                        ViewCompat.setScaleY(defaultProgressView, alpha);
                        ViewCompat.setAlpha(defaultProgressView, alpha);
                    }
                    if (overScrollTop < mTotalDragDistance) {
                        if (mScale) {
                            setAnimationProgress(overScrollTop / mTotalDragDistance);
                        }
                        if (mOnPullFromStartListener != null) {
                            mOnPullFromStartListener.onPullEnable(false);
                        }
                    } else {
                        if (mOnPullFromStartListener != null) {
                            mOnPullFromStartListener.onPullEnable(true);
                        }
                    }
                    setTargetOffsetTopAndBottom(targetY - mCurrentTargetOffsetTop,
                            true);
                }
                break;
            }
            case MotionEventCompat.ACTION_POINTER_DOWN: {
                final int index = MotionEventCompat.getActionIndex(ev);
                mActivePointerId = MotionEventCompat.getPointerId(ev, index);
                break;
            }

            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mActivePointerId == INVALID_POINTER) {
                    if (action == MotionEvent.ACTION_UP) {
                        Log.e(LOG_TAG,
                                "Got ACTION_UP event but don't have an active pointer id.");
                    }
                    return false;
                }
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev,
                        mActivePointerId);
                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float overScrollTop = (y - mInitialMotionY) * DRAG_RATE;
                mIsBeingDragged = false;
                if (overScrollTop > mTotalDragDistance) {
                    setRefreshing(true, true /* notify */);
                } else {
                    mIsRefreshing = false;
                    AnimationListener listener = null;
                    if (!mScale) {
                        listener = new AnimationListener() {

                            @Override
                            public void onAnimationStart(Animation animation) {
                            }

                            @Override
                            public void onAnimationEnd(Animation animation) {
                                if (!mScale) {
                                    startScaleDownAnimation(null);
                                }
                            }

                            @Override
                            public void onAnimationRepeat(Animation animation) {
                            }

                        };
                    }
                    animateOffsetToStartPosition(mCurrentTargetOffsetTop, listener);
                }
                mActivePointerId = INVALID_POINTER;
                return false;
            }
        }

        return true;
    }

    /**
     * handle the touch event during pushing loading more
     *
     * @param ev
     * @param action
     * @return
     */
    private boolean handlePushTouchEvent(MotionEvent ev, int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                mIsBeingDragged = false;
                Log.d(LOG_TAG, "debug:onTouchEvent ACTION_DOWN");
                break;
            case MotionEvent.ACTION_MOVE: {
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev,
                        mActivePointerId);
                if (pointerIndex < 0) {
                    Log.e(LOG_TAG,
                            "Got ACTION_MOVE event but have an invalid active pointer id.");
                    return false;
                }
                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float overScrollBottom = (mInitialMotionY - y) * DRAG_RATE;
                if (mIsBeingDragged) {
                    pushDistance = (int) overScrollBottom;
                    updateFooterViewPosition();
                    if (mOnPullFromEndListener != null) {
                        mOnPullFromEndListener
                                .onPushEnable(pushDistance >= mFooterViewHeight);
                    }
                }
                break;
            }
            case MotionEventCompat.ACTION_POINTER_DOWN: {
                final int index = MotionEventCompat.getActionIndex(ev);
                mActivePointerId = MotionEventCompat.getPointerId(ev, index);
                break;
            }

            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mActivePointerId == INVALID_POINTER) {
                    if (action == MotionEvent.ACTION_UP) {
                        Log.e(LOG_TAG,
                                "Got ACTION_UP event but don't have an active pointer id.");
                    }
                    return false;
                }
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev,
                        mActivePointerId);
                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float overscrollBottom = (mInitialMotionY - y) * DRAG_RATE;
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                if (overscrollBottom < mFooterViewHeight
                        || mOnPullFromEndListener == null) {// cancel
                    pushDistance = 0;
                } else {
                    pushDistance = mFooterViewHeight;
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                    updateFooterViewPosition();
                    if (pushDistance == mFooterViewHeight
                            && mOnPullFromEndListener != null) {
                        mIsLoadingMore = true;
                        mOnPullFromEndListener.onLoadMore();
                    }
                } else {
                    animatorFooterToBottom((int) overscrollBottom, pushDistance);
                }
                return false;
            }
        }
        return true;
    }

    /**
     * animation of footer after gesture
     *
     * @param start
     * @param end
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void animatorFooterToBottom(int start, final int end) {
        ValueAnimator valueAnimator = ValueAnimator.ofInt(start, end);
        valueAnimator.setDuration(150);
        valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {

            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                // update
                pushDistance = (Integer) valueAnimator.getAnimatedValue();

            }
        });
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (end > 0 && mOnPullFromEndListener != null) {
                    // start loading more
                    mIsLoadingMore = true;
                    mOnPullFromEndListener.onLoadMore();
                } else {
                    resetTargetLayout();
                    mIsLoadingMore = false;
                }
                updateFooterViewPosition();
            }
        });
        valueAnimator.setInterpolator(mDecelerateInterpolator);
        valueAnimator.start();
    }

    /**
     * Stop loading more after loading finished
     *
     */
    public void stopLoadingMore() {
        if (mIsLoadingMore) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                mIsLoadingMore = false;
                pushDistance = 0;
                updateFooterViewPosition();
            } else {
                animatorFooterToBottom(mFooterViewHeight, 0);
            }
        }
    }

    private void animateOffsetToCorrectPosition(int from,
                                                AnimationListener listener) {
        mFrom = from;
        mAnimateToCorrectPosition.reset();
        mAnimateToCorrectPosition.setDuration(ANIMATE_TO_TRIGGER_DURATION);
        mAnimateToCorrectPosition.setInterpolator(mDecelerateInterpolator);
        if (listener != null) {
            mHeadViewContainer.setAnimationListener(listener);
        }
        mHeadViewContainer.clearAnimation();
        mHeadViewContainer.startAnimation(mAnimateToCorrectPosition);
    }

    private void animateOffsetToStartPosition(int from,
                                              AnimationListener listener) {
        if (mScale) {
            startScaleDownReturnToStartAnimation(from, listener);
        } else {
            mFrom = from;
            mAnimateToStartPosition.reset();
            mAnimateToStartPosition.setDuration(ANIMATE_TO_START_DURATION);
            mAnimateToStartPosition.setInterpolator(mDecelerateInterpolator);
            if (listener != null) {
                mHeadViewContainer.setAnimationListener(listener);
            }
            mHeadViewContainer.clearAnimation();
            mHeadViewContainer.startAnimation(mAnimateToStartPosition);
        }
        resetTargetLayoutDelay(ANIMATE_TO_START_DURATION);
    }

    /**
     * reset the layout position of the target view with time delay
     *
     * @param delay
     */
    public void resetTargetLayoutDelay(int delay) {
        new Handler().postDelayed(new Runnable() {

            @Override
            public void run() {
                resetTargetLayout();
            }
        }, delay);
    }

    /**
     * reset the layout position of the target view, header view and footer view
     */
    public void resetTargetLayout() {
        final int width = getMeasuredWidth();
        final int height = getMeasuredHeight();
        final View child = mTarget;
        final int childLeft = getPaddingLeft();
        final int childTop = getPaddingTop();
        final int childWidth = child.getWidth() - getPaddingLeft() - getPaddingRight();
        final int childHeight = child.getHeight() - getPaddingTop() - getPaddingBottom();
        child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);

        int headViewWidth = mHeadViewContainer.getMeasuredWidth();
        int headViewHeight = mHeadViewContainer.getMeasuredHeight();
        mHeadViewContainer.layout((width / 2 - headViewWidth / 2), -headViewHeight, (width / 2 + headViewWidth / 2), 0);
        int footViewWidth = mFooterViewContainer.getMeasuredWidth();
        int footViewHeight = mFooterViewContainer.getMeasuredHeight();
        mFooterViewContainer.layout((width / 2 - footViewWidth / 2), height, (width / 2 + footViewWidth / 2), height + footViewHeight);
    }

    private final Animation mAnimateToCorrectPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            int targetTop = 0;
            int endTarget = 0;
            if (!mUsingCustomStart) {
                endTarget = (int) (mSpinnerFinalOffset - Math
                        .abs(mOriginalOffsetTop));
            } else {
                endTarget = (int) mSpinnerFinalOffset;
            }
            targetTop = (mFrom + (int) ((endTarget - mFrom) * interpolatedTime));
            int offset = targetTop - mHeadViewContainer.getTop();
            setTargetOffsetTopAndBottom(offset, false /* requires update */);
        }

        @Override
        public void setAnimationListener(AnimationListener listener) {
            super.setAnimationListener(listener);
        }
    };

    private void moveToStart(float interpolatedTime) {
        int targetTop = 0;
        targetTop = (mFrom + (int) ((mOriginalOffsetTop - mFrom) * interpolatedTime));
        int offset = targetTop - mHeadViewContainer.getTop();
        setTargetOffsetTopAndBottom(offset, false /* requires update */);
    }

    private final Animation mAnimateToStartPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            moveToStart(interpolatedTime);
        }
    };

    private void startScaleDownReturnToStartAnimation(int from, AnimationListener listener) {
        mFrom = from;
        mStartingScale = ViewCompat.getScaleX(mHeadViewContainer);
        mScaleDownToStartAnimation = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime,
                                            Transformation t) {
                float targetScale = (mStartingScale + (-mStartingScale * interpolatedTime));
                setAnimationProgress(targetScale);
                moveToStart(interpolatedTime);
            }
        };
        mScaleDownToStartAnimation.setDuration(SCALE_DOWN_DURATION);
        if (listener != null) {
            mHeadViewContainer.setAnimationListener(listener);
        }
        mHeadViewContainer.clearAnimation();
        mHeadViewContainer.startAnimation(mScaleDownToStartAnimation);
    }

    private void setTargetOffsetTopAndBottom(int offset, boolean requiresUpdate) {
        mHeadViewContainer.bringToFront();
        mHeadViewContainer.offsetTopAndBottom(offset);
        mCurrentTargetOffsetTop = mHeadViewContainer.getTop();
        if (requiresUpdate && Build.VERSION.SDK_INT < 11) {
            invalidate();
        }
        updatePullRefreshListenerCallBack();
    }

    private void updateFooterViewPosition() {
        mFooterViewContainer.setVisibility(View.VISIBLE);
        mFooterViewContainer.bringToFront();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            mFooterViewContainer.getParent().requestLayout();
        }
        mFooterViewContainer.offsetTopAndBottom(-pushDistance);
        updatePushDistanceListener();
    }

    private void updatePushDistanceListener() {
        if (mOnPullFromEndListener != null) {
            mOnPullFromEndListener.onPushDistance(pushDistance);
        }
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = MotionEventCompat.getActionIndex(ev);
        final int pointerId = MotionEventCompat.getPointerId(ev, pointerIndex);
        if (pointerId == mActivePointerId) {
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mActivePointerId = MotionEventCompat.getPointerId(ev,
                    newPointerIndex);
        }
    }

    /**
     * Pull refresh head view's container
     */
    private class HeadViewContainer extends RelativeLayout {

        private AnimationListener mListener;

        public HeadViewContainer(Context context) {
            super(context);
        }

        public void setAnimationListener(AnimationListener listener) {
            mListener = listener;
        }

        @Override
        public void onAnimationStart() {
            super.onAnimationStart();
            if (mListener != null) {
                mListener.onAnimationStart(getAnimation());
            }
        }

        @Override
        public void onAnimationEnd() {
            super.onAnimationEnd();
            if (mListener != null) {
                mListener.onAnimationEnd(getAnimation());
            }
        }
    }

    /**
     * check if teh target view should scroll with the gesture.
     *
     * @return
     */
    public boolean isTargetScrollWithLayout() {
        return targetScrollWithLayout || mMode == Mode.DISABLED;
    }

    /**
     * set if the target view should scroll with the gesture.
     *
     * @param targetScrollWithLayout
     */
    public void setTargetScrollWithLayout(boolean targetScrollWithLayout) {
        this.targetScrollWithLayout = targetScrollWithLayout;
    }

    /**
     * pull refresh listener
     */
    public interface OnPullFromStartListener {
        void onRefresh();

        void onPullDistance(int distance);

        void onPullEnable(boolean enable);
    }

    /**
     * push load more listener
     */
    public interface OnPullFromEndListener {
        void onLoadMore();

        void onPushDistance(int distance);

        void onPushEnable(boolean enable);
    }

    public class OnPullRefreshListenerAdapter implements OnPullFromStartListener {

        @Override
        public void onRefresh() {

        }

        @Override
        public void onPullDistance(int distance) {

        }

        @Override
        public void onPullEnable(boolean enable) {

        }

    }

    public class OnPushLoadMoreListenerAdapter implements
            OnPullFromEndListener {

        @Override
        public void onLoadMore() {

        }

        @Override
        public void onPushDistance(int distance) {

        }

        @Override
        public void onPushEnable(boolean enable) {

        }

    }

    /**
     * set default progress circle's color
     *
     * @param color
     */
    public void setDefaultCircleProgressColor(int color) {
        if (usingDefaultHeader) {
            defaultProgressView.setProgressColor(color);
        }
    }

    /**
     * set default progress circle's background color
     *
     * @param color
     */
    public void setDefaultCircleBackgroundColor(int color) {
        if (usingDefaultHeader) {
            defaultProgressView.setCircleBackgroundColor(color);
        }
    }

    public void setDefaultCircleShadowColor(int color) {
        if (usingDefaultHeader) {
            defaultProgressView.setShadowColor(color);
        }
    }

    /**
     * default circle progress view
     */
    public class CircleProgressView extends View implements Runnable {

        private static final int INVALIDATE_PERIOD = 16;// period of invalidate
        private Paint progressPaint;
        private Paint bgPaint;
        private int width;
        private int height;

        private boolean isOnDraw = false;
        private boolean isRunning = false;
        private int startAngle = 0;
        private int speed = 8;
        private RectF ovalRect = null;
        private RectF bgRect = null;
        private int swipeAngle;
        private int progressColor = 0xffcccccc;
        private int circleBackgroundColor = 0xffffffff;
        private int shadowColor = 0xff999999;

        public CircleProgressView(Context context) {
            super(context);
        }

        public CircleProgressView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public CircleProgressView(Context context, AttributeSet attrs,
                                  int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawArc(getBgRect(), 0, 360, false, createBgPaint());
            int index = startAngle / 360;
            if (index % 2 == 0) {
                swipeAngle = (startAngle % 720) / 2;
            } else {
                swipeAngle = 360 - (startAngle % 720) / 2;
            }
            canvas.drawArc(getOvalRect(), startAngle, swipeAngle, false,
                    createPaint());
        }

        private RectF getBgRect() {
            width = getWidth();
            height = getHeight();
            if (bgRect == null) {
                int offset = (int) (density * 2);
                bgRect = new RectF(offset, offset, width - offset, height
                        - offset);
            }
            return bgRect;
        }

        private RectF getOvalRect() {
            width = getWidth();
            height = getHeight();
            if (ovalRect == null) {
                int offset = (int) (density * 8);
                ovalRect = new RectF(offset, offset, width - offset, height
                        - offset);
            }
            return ovalRect;
        }

        public void setProgressColor(int progressColor) {
            this.progressColor = progressColor;
        }

        public void setCircleBackgroundColor(int circleBackgroundColor) {
            this.circleBackgroundColor = circleBackgroundColor;
        }

        public void setShadowColor(int shadowColor) {
            this.shadowColor = shadowColor;
        }

        private Paint createPaint() {
            if (this.progressPaint == null) {
                progressPaint = new Paint();
                progressPaint.setStrokeWidth((int) (density * 3));
                progressPaint.setStyle(Paint.Style.STROKE);
                progressPaint.setAntiAlias(true);
            }
            progressPaint.setColor(progressColor);
            return progressPaint;
        }

        private Paint createBgPaint() {
            if (this.bgPaint == null) {
                bgPaint = new Paint();
                bgPaint.setColor(circleBackgroundColor);
                bgPaint.setStyle(Paint.Style.FILL);
                bgPaint.setAntiAlias(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    this.setLayerType(LAYER_TYPE_SOFTWARE, bgPaint);
                }
                bgPaint.setShadowLayer(4.0f, 0.0f, 2.0f, shadowColor);
            }
            return bgPaint;
        }

        public void setPullDistance(int distance) {
            this.startAngle = distance * 2;
            postInvalidate();
        }

        @Override
        public void run() {
            while (isOnDraw) {
                isRunning = true;
                long startTime = System.currentTimeMillis();
                startAngle += speed;
                postInvalidate();
                long time = System.currentTimeMillis() - startTime;
                if (time < INVALIDATE_PERIOD) {
                    try {
                        Thread.sleep(INVALIDATE_PERIOD - time);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public void setOnDraw(boolean isOnDraw) {
            this.isOnDraw = isOnDraw;
        }

        public void setSpeed(int speed) {
            this.speed = speed;
        }

        public boolean isRunning() {
            return isRunning;
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
        }

        @Override
        protected void onDetachedFromWindow() {
            isOnDraw = false;
            super.onDetachedFromWindow();
        }

    }

    /**
     * the mode of the refresh layout
     */
    public static enum Mode {

        /**
         * Disable all Pull-to-Refresh gesture and Refreshing handling
         */
        DISABLED(0x0),

        /**
         * Only allow the user to Pull from the start of the Refreshable View to
         * refresh. The start is either the Top or Left, depending on the
         * scrolling direction.
         */
        PULL_FROM_START(0x1),

        /**
         * Only allow the user to Pull from the end of the Refreshable View to
         * refresh. The start is either the Bottom or Right, depending on the
         * scrolling direction.
         */
        PULL_FROM_END(0x2),

        /**
         * Allow the user to both Pull from the start, from the end to refresh.
         */
        BOTH(0x3);

        /**
         * Maps an int to a specific mode. This is needed when saving state, or
         * inflating the view from XML where the mode is given through a attr
         * int.
         *
         * @param modeInt - int to map a Mode to
         * @return Mode that modeInt maps to, or PULL_FROM_START by default.
         */
        static Mode mapIntToValue(final int modeInt) {
            for (Mode value : Mode.values()) {
                if (modeInt == value.getIntValue()) {
                    return value;
                }
            }

            // If not, return default
            return getDefault();
        }

        static Mode getDefault() {
            return PULL_FROM_START;
        }

        private int mIntValue;

        // The modeInt values need to match those from attrs.xml
        Mode(int modeInt) {
            mIntValue = modeInt;
        }

        int getIntValue() {
            return mIntValue;
        }

    }
}
