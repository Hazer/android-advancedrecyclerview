/*
 *    Copyright (C) 2015 Haruki Hasegawa
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.h6ah4i.android.widget.advrecyclerview.swipeable;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import com.h6ah4i.android.widget.advrecyclerview.animator.SwipeDismissItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.utils.CustomRecyclerViewUtils;
import com.h6ah4i.android.widget.advrecyclerview.utils.ViewUtils;
import com.h6ah4i.android.widget.advrecyclerview.utils.WrapperAdapterUtils;

public class RecyclerViewSwipeManager {
    private static final String TAG = "RecyclerViewSwipeManager";


    public static final int RESULT_NONE = 0;
    public static final int RESULT_CANCELED = 1;
    public static final int RESULT_SWIPED_LEFT = 2;
    public static final int RESULT_SWIPED_RIGHT = 3;

    public static final int REACTION_CAN_NOT_SWIPE_LEFT = (0 << 0);
    public static final int REACTION_CAN_NOT_SWIPE_LEFT_WITH_RUBBER_BAND_EFFECT = (1 << 0);
    public static final int REACTION_CAN_SWIPE_LEFT = (2 << 0);
    public static final int REACTION_CAN_NOT_SWIPE_RIGHT = (0 << 16);
    public static final int REACTION_CAN_NOT_SWIPE_RIGHT_WITH_RUBBER_BAND_EFFECT = (1 << 16);
    public static final int REACTION_CAN_SWIPE_RIGHT = (2 << 16);

    public static final int DRAWABLE_SWIPE_NEUTRAL_BACKGROUND = 0;
    public static final int DRAWABLE_SWIPE_LEFT_BACKGROUND = 1;
    public static final int DRAWABLE_SWIPE_RIGHT_BACKGROUND = 2;

    public static final int AFTER_SWIPE_REACTION_DEFAULT = 0;
    public static final int AFTER_SWIPE_REACTION_REMOVE_ITEM = 1;
    public static final int AFTER_SWIPE_REACTION_MOVE_TO_SWIPED_DIRECTION = 2;

    public static final float OUTSIDE_OF_THE_WINDOW_LEFT = -Float.MAX_VALUE;
    public static final float OUTSIDE_OF_THE_WINDOW_RIGHT = Float.MAX_VALUE;

    public static final int STATE_FLAG_SWIPING = (1 << 0);
    public static final int STATE_FLAG_IS_ACTIVE = (1 << 1);
    public static final int STATE_FLAG_IS_UPDATED = (1 << 31);


    private static final int MIN_DISTANCE_TOUCH_SLOP_MUL = 10;
    private static final int SLIDE_ITEM_IMMEDIATELY_SET_TRANSLATION_THRESHOLD_DP = 8;

    private static final boolean LOCAL_LOGV = false;
    private static final boolean LOCAL_LOGD = false;

    private RecyclerView.OnItemTouchListener mInternalUseOnItemTouchListener;
    private RecyclerView mRecyclerView;

    private long mReturnToDefaultPositionAnimationDuration = 300;
    private long mMoveToOutsideWindowAnimationDuration = 200;

    private int mTouchSlop;
    private int mMinFlingVelocity; // [pixels per second]
    private int mMaxFlingVelocity; // [pixels per second]
    private int mInitialTouchX;
    private int mInitialTouchY;
    private long mCheckingTouchSlop = RecyclerView.NO_ID;

    private ItemSlidingAnimator mItemSlideAnimator;
    private SwipeableItemWrapperAdapter mAdapter;
    private RecyclerView.ViewHolder mSwipingItem;
    private Rect mSwipingItemMargins = new Rect();
    private int mTouchedItemOffsetX;
    private int mLastTouchX;
    private int mSwipingItemReactionType;
    private VelocityTracker mVelocityTracker;
    private SwipingItemOperator mSwipingItemOperator;

    public RecyclerViewSwipeManager() {
        mInternalUseOnItemTouchListener = new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                return RecyclerViewSwipeManager.this.onInterceptTouchEvent(rv, e);
            }

            @Override
            public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                RecyclerViewSwipeManager.this.onTouchEvent(rv, e);
            }
        };
        mItemSlideAnimator = new ItemSlidingAnimator();
        mVelocityTracker = VelocityTracker.obtain();
    }

    public SwipeableItemWrapperAdapter createWrappedAdapter(RecyclerView.Adapter adapter) {
        if (mAdapter != null) {
            throw new IllegalStateException("already have a wrapped adapter");
        }

        mAdapter = new SwipeableItemWrapperAdapter(this, adapter);

        return mAdapter;
    }

    public boolean isReleased() {
        return (mInternalUseOnItemTouchListener == null);
    }

    public void attachRecyclerView(RecyclerView rv) {
        if (rv == null) {
            throw new IllegalArgumentException("RecyclerView cannot be null");
        }

        if (isReleased()) {
            throw new IllegalStateException("Accessing released object");
        }

        if (mRecyclerView != null) {
            throw new IllegalStateException("RecyclerView instance has already been set");
        }

        if (mAdapter == null || getSwipeableItemWrapperAdapter(rv) != mAdapter) {
            throw new IllegalStateException("adapter is not set properly");
        }

        mRecyclerView = rv;
        mRecyclerView.addOnItemTouchListener(mInternalUseOnItemTouchListener);

        final ViewConfiguration vc = ViewConfiguration.get(rv.getContext());

        mTouchSlop = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();

        mItemSlideAnimator.setImmediatelySetTranslationThreshold(
                (int) (rv.getResources().getDisplayMetrics().density * SLIDE_ITEM_IMMEDIATELY_SET_TRANSLATION_THRESHOLD_DP + 0.5f));
    }

    public void release() {
        if (mRecyclerView != null && mInternalUseOnItemTouchListener != null) {
            mRecyclerView.removeOnItemTouchListener(mInternalUseOnItemTouchListener);
        }
        mInternalUseOnItemTouchListener = null;

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        if (mItemSlideAnimator != null) {
            mItemSlideAnimator.endAnimations();
            mItemSlideAnimator = null;
        }

        mAdapter = null;
        mRecyclerView = null;
    }

    public boolean isSwiping() {
        return (mSwipingItem != null);
    }

    /*package*/ boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        final int action = MotionEventCompat.getActionMasked(e);

        if (LOCAL_LOGV) {
            Log.v(TAG, "onInterceptTouchEvent() action = " + action);
        }

        switch (action) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isSwiping()) {
                    handleActionUpOrCancelWhileSwiping(e);
                    return true;
                } else {
                    handleActionUpOrCancelWhileNotSwiping();
                }
                break;

            case MotionEvent.ACTION_DOWN:
                if (!isSwiping()) {
                    handleActionDown(rv, e);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (isSwiping()) {
                    // NOTE: The first ACTION_MOVE event will come here. (maybe a bug of RecyclerView?)
                    handleActionMoveWhileSwiping(e);
                    return true;
                } else {
                    if (handleActionMoveWhileNotSwiping(rv, e)) {
                        return true;
                    }
                }
                break;
        }

        return false;
    }

    /*package*/ void onTouchEvent(RecyclerView rv, MotionEvent e) {
        final int action = MotionEventCompat.getActionMasked(e);

        if (LOCAL_LOGV) {
            Log.v(TAG, "onTouchEvent() action = " + action);
        }

        if (!isSwiping()) {
            // Log.w(TAG, "onTouchEvent() - unexpected state");
            return;
        }

        switch (action) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handleActionUpOrCancelWhileSwiping(e);
                break;

            case MotionEvent.ACTION_MOVE:
                handleActionMoveWhileSwiping(e);
                break;
        }
    }

    private boolean handleActionDown(RecyclerView rv, MotionEvent e) {
        final RecyclerView.Adapter adapter = rv.getAdapter();
        final RecyclerView.ViewHolder holder = CustomRecyclerViewUtils.findChildViewHolderUnderWithTranslation(rv, e.getX(), e.getY());

        if (!(holder instanceof SwipeableItemViewHolder)) {
            return false;
        }

        final int itemPosition = holder.getPosition();

        // verify the touched item is valid state
        if (!(itemPosition >= 0 && itemPosition < adapter.getItemCount())) {
            return false;
        }
        if (holder.getItemId() != adapter.getItemId(itemPosition)) {
            return false;
        }

        final int touchX = (int) (e.getX() + 0.5f);
        final int touchY = (int) (e.getY() + 0.5f);

        final View view = holder.itemView;
        final int translateX = (int) (ViewCompat.getTranslationX(view) + 0.5f);
        final int translateY = (int) (ViewCompat.getTranslationY(view) + 0.5f);
        final int viewX = touchX - (view.getLeft() + translateX);
        final int viewY = touchY - (view.getTop() + translateY);

        final int reactionType = mAdapter.getSwipeReactionType(holder, viewX, viewY);

        if (reactionType == 0) {
            return false;
        }

        mInitialTouchX = touchX;
        mInitialTouchY = touchY;
        mCheckingTouchSlop = holder.getItemId();
        mSwipingItemReactionType = reactionType;

        return true;
    }

    private static SwipeableItemWrapperAdapter getSwipeableItemWrapperAdapter(RecyclerView rv) {
        return WrapperAdapterUtils.findWrappedAdapter(rv.getAdapter(), SwipeableItemWrapperAdapter.class);
    }

    private void handleActionUpOrCancelWhileNotSwiping() {
        mCheckingTouchSlop = RecyclerView.NO_ID;
        mSwipingItemReactionType = 0;
    }

    private boolean handleActionUpOrCancelWhileSwiping(MotionEvent e) {
        mLastTouchX = (int) (e.getX() + 0.5f);

        int result = RESULT_CANCELED;

        if (MotionEventCompat.getActionMasked(e) == MotionEvent.ACTION_UP) {
            final int viewWidth = mSwipingItem.itemView.getWidth();
            final float distance = mLastTouchX - mInitialTouchX;
            final float absDistance = Math.abs(distance);

            mVelocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity); // 1000: pixels per second

            final float xVelocity = mVelocityTracker.getXVelocity();
            final float absXVelocity = Math.abs(xVelocity);

            if ((absDistance > (mTouchSlop * MIN_DISTANCE_TOUCH_SLOP_MUL)) &&
                    ((distance * xVelocity) > 0.0f) &&
                    (absXVelocity <= mMaxFlingVelocity) &&
                    ((absDistance > (viewWidth / 2)) || (absXVelocity >= mMinFlingVelocity))) {

                if ((distance < 0) && SwipeReactionUtils.canSwipeLeft(mSwipingItemReactionType)) {
                    result = RESULT_SWIPED_LEFT;
                } else if ((distance > 0) && SwipeReactionUtils.canSwipeRight(mSwipingItemReactionType)) {
                    result = RESULT_SWIPED_RIGHT;
                }
            }
        }

        if (LOCAL_LOGD) {
            Log.d(TAG, "swping finished  --- result = " + result);
        }

        finishSwiping(result);

        return true;
    }

    private boolean handleActionMoveWhileNotSwiping(RecyclerView rv, MotionEvent e) {
        if (mCheckingTouchSlop == RecyclerView.NO_ID) {
            return false;
        }

        if (Math.abs(e.getY() - mInitialTouchY) > mTouchSlop) {
            // scrolling occurred
            mCheckingTouchSlop = RecyclerView.NO_ID;
            return false;
        }

        if (Math.abs(e.getX() - mInitialTouchX) <= mTouchSlop) {
            return false;
        }

        final RecyclerView.ViewHolder holder = CustomRecyclerViewUtils.findChildViewHolderUnderWithTranslation(rv, e.getX(), e.getY());

        if (holder == null || holder.getItemId() != mCheckingTouchSlop) {
            mCheckingTouchSlop = RecyclerView.NO_ID;
            return false;
        }

        if (LOCAL_LOGD) {
            Log.d(TAG, "swiping started");
        }

        startSwiping(rv, e, holder);

        return true;
    }

    private void handleActionMoveWhileSwiping(MotionEvent e) {
        mLastTouchX = (int) (e.getX() + 0.5f);
        mVelocityTracker.addMovement(e);

        final int swipeDistance = mLastTouchX - (mTouchedItemOffsetX + mSwipingItemMargins.left);

        mSwipingItemOperator.update(swipeDistance);
    }

    private void startSwiping(RecyclerView rv, MotionEvent e, RecyclerView.ViewHolder holder) {
        mSwipingItem = holder;
        mLastTouchX = (int) (e.getX() + 0.5f);
        mTouchedItemOffsetX = mLastTouchX - holder.itemView.getLeft();
        CustomRecyclerViewUtils.getLayoutMargins(holder.itemView, mSwipingItemMargins);

        mSwipingItemOperator = new SwipingItemOperator(this, mSwipingItem, mSwipingItemReactionType);
        mSwipingItemOperator.start();

        mVelocityTracker.clear();
        mVelocityTracker.addMovement(e);

        // raise onSwipeItemStarted() event
        mAdapter.onSwipeItemStarted(this, holder);
    }

    private void finishSwiping(int result) {
        final RecyclerView.ViewHolder swipingItem = mSwipingItem;

        if (swipingItem == null) {
            return;
        }

        mVelocityTracker.clear();

        mSwipingItem = null;
        mLastTouchX = 0;
        mInitialTouchX = 0;
        mTouchedItemOffsetX = 0;
        mCheckingTouchSlop = RecyclerView.NO_ID;
        mSwipingItemReactionType = 0;

        if (mSwipingItemOperator != null) {
            mSwipingItemOperator.finish();
            mSwipingItemOperator = null;
        }

        final boolean toLeft = (result == RESULT_SWIPED_LEFT);
        int afterReaction = AFTER_SWIPE_REACTION_DEFAULT;

        if (mAdapter != null) {
            afterReaction = mAdapter.onSwipeItemFinished(swipingItem, result);
        }

        afterReaction = correctAfterReaction(result, afterReaction);

        switch (afterReaction) {
            case AFTER_SWIPE_REACTION_DEFAULT:
                mItemSlideAnimator.slideToDefaultPosition(swipingItem, true, mReturnToDefaultPositionAnimationDuration);
                break;
            case AFTER_SWIPE_REACTION_MOVE_TO_SWIPED_DIRECTION:
                mItemSlideAnimator.slideToOutsideOfWindow(
                        swipingItem, toLeft, true, mMoveToOutsideWindowAnimationDuration);
                break;
            case AFTER_SWIPE_REACTION_REMOVE_ITEM: {
                final RecyclerView.ItemAnimator itemAnimator = mRecyclerView.getItemAnimator();

                final long removeAnimationDuration = (itemAnimator != null) ? itemAnimator.getRemoveDuration() : 0;

                if (supportsViewPropertyAnimator()) {
                    final long moveAnimationDuration = (itemAnimator != null) ? itemAnimator.getMoveDuration() : 0;

                    final RemovingItemDecorator decorator = new RemovingItemDecorator(
                            mRecyclerView, swipingItem, removeAnimationDuration, moveAnimationDuration);

                    decorator.setMoveAnimationInterpolator(SwipeDismissItemAnimator.MOVE_INTERPOLATOR);
                    decorator.start();
                }

                mItemSlideAnimator.slideToOutsideOfWindow(
                        swipingItem, toLeft, true, removeAnimationDuration);
            }
            break;
            default:
                throw new IllegalStateException("Unknwon after reaction type: " + afterReaction);
        }

        if (mAdapter != null) {
            mAdapter.onSwipeItemFinished2(swipingItem, result, afterReaction);
        }
    }

    private static int correctAfterReaction(int result, int afterReaction) {
        if ((afterReaction == AFTER_SWIPE_REACTION_MOVE_TO_SWIPED_DIRECTION) ||
                (afterReaction == AFTER_SWIPE_REACTION_REMOVE_ITEM)) {
            if (!((result == RESULT_SWIPED_LEFT) || (result == RESULT_SWIPED_RIGHT))) {
                afterReaction = AFTER_SWIPE_REACTION_DEFAULT;
            }
        }

        return afterReaction;
    }

    public void cancelSwipe() {
        finishSwiping(RESULT_CANCELED);
    }

    public boolean isAnimationRunning(RecyclerView.ViewHolder item) {
        return (mItemSlideAnimator != null) && (mItemSlideAnimator.isRunning(item));
    }

    public void slideItem(RecyclerView.ViewHolder holder, float amount, boolean shouldAnimate) {
        if (amount == OUTSIDE_OF_THE_WINDOW_LEFT) {
            mItemSlideAnimator.slideToOutsideOfWindow(holder, true, shouldAnimate, mMoveToOutsideWindowAnimationDuration);
        } else if (amount == OUTSIDE_OF_THE_WINDOW_RIGHT) {
            mItemSlideAnimator.slideToOutsideOfWindow(holder, false, shouldAnimate, mMoveToOutsideWindowAnimationDuration);
        } else if (amount == 0.0f) {
            mItemSlideAnimator.slideToDefaultPosition(holder, shouldAnimate, mReturnToDefaultPositionAnimationDuration);
        } else {
            mItemSlideAnimator.slideToSpecifiedPosition(holder, amount);
        }
    }

    public long getReturnToDefaultPositionAnimationDuration() {
        return mReturnToDefaultPositionAnimationDuration;
    }

    public void setReturnToDefaultPositionAnimationDuration(long returnToDefaultPositionAnimationDuration) {
        mReturnToDefaultPositionAnimationDuration = returnToDefaultPositionAnimationDuration;
    }

    public long getMoveToOutsideWindowAnimationDuration() {
        return mMoveToOutsideWindowAnimationDuration;
    }

    public void setMoveToOutsideWindowAnimationDuration(long moveToOutsideWindowAnimationDuration) {
        mMoveToOutsideWindowAnimationDuration = moveToOutsideWindowAnimationDuration;
    }

    /*package*/ void applySlideItem(RecyclerView.ViewHolder holder, float prevAmount, float amount, boolean shouldAnimate) {
        final SwipeableItemViewHolder holder2 = (SwipeableItemViewHolder) holder;
        final View itemView = holder.itemView;
        final View containerView = holder2.getSwipeableContainerView();

        if (containerView == null) {
            return;
        }

        final int reqBackgroundType;

        if (amount == +0.0f || amount == -0.0f) {
            if (prevAmount == +0.0f || prevAmount == -0.0f) {
                reqBackgroundType = DRAWABLE_SWIPE_NEUTRAL_BACKGROUND;
            } else {
                reqBackgroundType = (prevAmount < 0)
                        ? DRAWABLE_SWIPE_LEFT_BACKGROUND
                        : DRAWABLE_SWIPE_RIGHT_BACKGROUND;
            }
        } else {
            reqBackgroundType = (amount < 0)
                    ? DRAWABLE_SWIPE_LEFT_BACKGROUND
                    : DRAWABLE_SWIPE_RIGHT_BACKGROUND;
        }

        final Drawable background = mAdapter.getSwipeBackgroundDrawable(holder, reqBackgroundType);

        if (amount == 0.0f) {
            slideItem(holder, amount, shouldAnimate);
            ViewUtils.setBackground(itemView, background);
        } else {
            ViewUtils.setBackground(itemView, background);
            slideItem(holder, amount, shouldAnimate);
        }
    }

    private static boolean supportsViewPropertyAnimator() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB;
    }
}
