package com.swiperefreshlayout;

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


import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
//import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;
import android.widget.AbsListView;


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
 * animation, it should call setRefreshing(true). To disable the gesture and progress
 * animation, call setEnabled(false) on the view.
 *
 * <p> This layout should be made the parent of the view that will be refreshed as a
 * result of the gesture and can only support one direct child. This view will
 * also be made the target of the gesture and will be forced to match both the
 * width and the height supplied in this layout. The SwipeRefreshLayout does not
 * provide accessibility events; instead, a menu item must be provided to allow
 * refresh of the content wherever this gesture is used.</p>
 */
public class SwipeRefreshLayout extends ViewGroup {
    private static final float ACCELERATE_INTERPOLATION_FACTOR = 1.5f;
    private static final float DECELERATE_INTERPOLATION_FACTOR = 2f;
    
    //status
    private boolean mRefreshing = false;
    private boolean mPulling = false;
    private boolean mReturningToStart = false;
    
    //anchor
    private int mOriginalTop = 0;//包含指示器的视图的top坐标
    private int mOriginalOffsetTop = 0; //起始时刻content的top坐标.
    private int mRefreshIndicatorHeight = 0;//indicator高度
    private int mTouchSlop;//滑动触发的最小距离
    private int mCurrentTargetOffsetTop;//当前时刻content的top坐标
    private int mRefreshStartPosition = 0;
    private int mFrom;
    private float mDistanceToTriggerSync = -1;
    private float mPrevY;
    
    //Pull down progress
    private float mFromPercentage = 0;
    private float mCurrPercentage = 0;
    
    //animate
    private int mShotAnimationDuration;
    private int mLongAnimationDuration;
    private final DecelerateInterpolator mDecelerateInterpolator;
    private final AccelerateInterpolator mAccelerateInterpolator;
    
    //Element
    private View mRefreshIndicator; //the indicator above the target
    private View mTarget; //the content that gets pulled down
    
    private OnRefreshListener mListener;
    private MotionEvent mDownEvent;

    // Target is returning to its start offset because it was cancelled or a
    // refresh was triggered.
    private static final int[] LAYOUT_ATTRS = new int[] {
        android.R.attr.enabled
    };

    private final Animation mAnimateToStartPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
        	mOriginalTop = mOriginalOffsetTop-mRefreshIndicatorHeight;
            int targetTop = 0;
            targetTop = (mFrom + (int)((mOriginalTop - mFrom) * interpolatedTime));
            int offset = targetTop - mTarget.getTop();
            final int currentTop = mTarget.getTop();
            if (offset + currentTop < mOriginalTop) {
                offset = mOriginalTop - currentTop;
            }
            setTargetOffsetTopAndBottom(offset);
        }
    };
    
    /**
     * 开始刷新时的回弹效果
     */
    private final Animation mAnimationToRefreshPosition = new Animation(){
    	@Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
    		mRefreshStartPosition = (int) (mOriginalOffsetTop-mRefreshIndicatorHeight+mDistanceToTriggerSync);
    		int targetTop = 0;
            targetTop = (mFrom + (int)((mRefreshStartPosition - mFrom) * interpolatedTime));
            int offset = targetTop - mTarget.getTop();
            final int currentTop = mTarget.getTop();
            if (offset + currentTop < mRefreshStartPosition) {
                offset = (int) (mRefreshStartPosition - currentTop);
            }
            setTargetOffsetTopAndBottom(offset);
    	}
    };

    private Animation mShrinkTrigger = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            float percent = mFromPercentage + ((0 - mFromPercentage) * interpolatedTime);
//            mProgressBar.setTriggerPercentage(percent);
        }
    };

    private final AnimationListener mReturnToStartPositionListener = new BaseAnimationListener() {
        @Override
        public void onAnimationEnd(Animation animation) {
            // Once the target content has returned to its start position, reset
            // the target offset to mOriginalOffsetTop
            mCurrentTargetOffsetTop = 0;
        }
    };
    
    private final AnimationListener mGoingToRefreshPositionListener = new BaseAnimationListener() {
    	@Override
        public void onAnimationEnd(Animation animation) {
    		mCurrentTargetOffsetTop = mRefreshStartPosition;
    	}
    };

    private final AnimationListener mShrinkAnimationListener = new BaseAnimationListener() {
        @Override
        public void onAnimationEnd(Animation animation) {
            mCurrPercentage = 0;
        }
    };

    private final Runnable mReturnToStartPosition = new Runnable() {

        @Override
        public void run() {
            mReturningToStart = true;
            animateOffsetToStartPosition(mCurrentTargetOffsetTop-mRefreshIndicatorHeight,
                    mReturnToStartPositionListener);
        }

    };
    
    private final Runnable mOffsetToRefreshPosition = new Runnable(){
    	@Override
        public void run() {
    		animateOffsetToRefreshPosition(mCurrentTargetOffsetTop-mRefreshIndicatorHeight,
            		mGoingToRefreshPositionListener);
        }
    };

    // Cancel the refresh gesture and animate everything back to its original state.
    private final Runnable mCancel = new Runnable() {

        @Override
        public void run() {
            mReturningToStart = true;
            animateOffsetToStartPosition(mCurrentTargetOffsetTop + getPaddingTop()-mRefreshIndicatorHeight,
                    mReturnToStartPositionListener);
        }

    };

    /**
     * Simple constructor to use when creating a SwipeRefreshLayout from code.
     * @param context
     */
    public SwipeRefreshLayout(Context context) {
        this(context, null);
    }

    /**
     * Constructor that is called when inflating SwipeRefreshLayout from XML.
     * @param context
     * @param attrs
     */
    public SwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mShotAnimationDuration = getResources().getInteger(
                android.R.integer.config_shortAnimTime);
        mLongAnimationDuration = getResources().getInteger(
                android.R.integer.config_longAnimTime);
        setWillNotDraw(false);
        mDecelerateInterpolator = new DecelerateInterpolator(DECELERATE_INTERPOLATION_FACTOR);
        mAccelerateInterpolator = new AccelerateInterpolator(ACCELERATE_INTERPOLATION_FACTOR);
        final TypedArray a = context.obtainStyledAttributes(attrs, LAYOUT_ATTRS);
        setEnabled(a.getBoolean(0, true));
        a.recycle();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(mCancel);
        removeCallbacks(mReturnToStartPosition);
        removeCallbacks(mOffsetToRefreshPosition);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mOffsetToRefreshPosition);
        removeCallbacks(mReturnToStartPosition);
        removeCallbacks(mCancel);
    }

    private void animateOffsetToStartPosition(int from, AnimationListener listener) {
        mFrom = from;
        mAnimateToStartPosition.reset();
        mAnimateToStartPosition.setDuration(mLongAnimationDuration);
        mAnimateToStartPosition.setAnimationListener(listener);
        mAnimateToStartPosition.setInterpolator(mDecelerateInterpolator);
        mTarget.startAnimation(mAnimateToStartPosition);
    }
    
    private void animateOffsetToRefreshPosition(int from, AnimationListener listener){
    	mFrom = from;
    	mAnimationToRefreshPosition.reset();
    	mAnimationToRefreshPosition.setDuration(mShotAnimationDuration);
    	mAnimationToRefreshPosition.setAnimationListener(listener);
    	mAnimationToRefreshPosition.setInterpolator(mDecelerateInterpolator);
        mTarget.startAnimation(mAnimationToRefreshPosition);
    }

    /**
     * Set the listener to be notified when a refresh is triggered via the swipe
     * gesture.
     */
    public void setOnRefreshListener(OnRefreshListener listener) {
        mListener = listener;
    }

    private void setTriggerPercentage(float percent) {
        if (percent == 0f) {
            // No-op. A null trigger means it's uninitialized, and setting it to zero-percent
            // means we're trying to reset state, so there's nothing to reset in this case.
            mCurrPercentage = 0;
            return;
        }
        mCurrPercentage = percent;
//        mProgressBar.setTriggerPercentage(percent);
    }


    /**
     * @return Whether the SwipeRefreshWidget is actively showing refresh
     *         progress.
     */
    public boolean isRefreshing() {
        return mRefreshing;
    }

    //控件初始化
    private void ensureTarget() {
        // Don't bother getting the parent height if the parent hasn't been laid out yet.
        if (mTarget == null) {
            if (getChildCount() > 1 && !isInEditMode()) {
                throw new IllegalStateException(
                        "SwipeRefreshLayout can host only one direct child");
            }
            mTarget = getChildAt(0);
            mOriginalOffsetTop = mTarget.getTop() + getPaddingTop();
        }
        if (mDistanceToTriggerSync == -1) {
            //TODO 初始化触发距离
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int width =  getMeasuredWidth();
        final int height = getMeasuredHeight();
        if(mRefreshIndicator != null){
        	mRefreshIndicatorHeight = mRefreshIndicator.getMeasuredHeight();
        }
        if (getChildCount() == 0) {
            return;
        }
        final View child = getChildAt(0);
        final int childLeft = getPaddingLeft();
        final int childTop = mCurrentTargetOffsetTop + getPaddingTop()-mRefreshIndicatorHeight;
        final int childWidth = width - getPaddingLeft() - getPaddingRight();
        final int childHeight = height - getPaddingTop() - getPaddingBottom()+mRefreshIndicatorHeight;
        child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        //计算指示器的规格
//        if(mRefreshIndicator != null) 
//        	mRefreshIndicator.measure(0, 0);
        if (getChildCount() > 1 && !isInEditMode()) {
            throw new IllegalStateException("SwipeRefreshLayout can host only one direct child");
        }
        if (getChildCount() > 0) {
            getChildAt(0).measure(
                    MeasureSpec.makeMeasureSpec(
                            getMeasuredWidth() - getPaddingLeft() - getPaddingRight(),
                            MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(
                            getMeasuredHeight() - getPaddingTop() - getPaddingBottom(),
                            MeasureSpec.EXACTLY));
        }
    }

    /**
     * @return Whether it is possible for the child view of this layout to
     *         scroll up. Override this if the child view is a custom view.
     */
    public boolean canChildScrollUp() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            if (mTarget instanceof AbsListView) {
                final AbsListView absListView = (AbsListView) mTarget;
                return absListView.getChildCount() > 0
                        && (absListView.getFirstVisiblePosition() > 0 || absListView.getChildAt(0)
                                .getTop() < absListView.getPaddingTop());
            } else {
                return mTarget.getScrollY() > 0;
            }
        } else {
            return ViewCompat.canScrollVertically(mTarget, -1);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        ensureTarget();
        boolean handled = false;
        if (mReturningToStart && ev.getAction() == MotionEvent.ACTION_DOWN) {
            mReturningToStart = false;
        }
        if (isEnabled() && !mReturningToStart && !canChildScrollUp()) {
            handled = onTouchEvent(ev);
        }
        return !handled ? super.onInterceptTouchEvent(ev) : handled;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean b) {
        // Nope.
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
    	if(mDistanceToTriggerSync == -1){
    		mDistanceToTriggerSync = mRefreshIndicatorHeight;
    	}
        final int action = event.getAction();
        boolean handled = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mCurrPercentage = 0;
                mDownEvent = MotionEvent.obtain(event);
                mPrevY = mDownEvent.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (mDownEvent != null && !mReturningToStart && !mRefreshing) {
                    final float eventY = event.getY();
                    float yDiff = eventY - mDownEvent.getY();
                    if(yDiff > mDistanceToTriggerSync*1.5){
                    	yDiff = (float) (mDistanceToTriggerSync*1.5);
                    }
                    if (yDiff > mTouchSlop||mPulling) {
                    	mPulling = true;
                    	if(mCurrentTargetOffsetTop +eventY - mPrevY
                    			- mOriginalOffsetTop > mDistanceToTriggerSync){
                    		if(mListener != null)
                        		mListener.canRefresh();
                    	}
                    	else if(mListener != null){
                    		mListener.canNotRefresh();
                    	}
                        setTriggerPercentage(
                                mAccelerateInterpolator.getInterpolation(
                                        yDiff / mDistanceToTriggerSync));
                        float offsetTop = mCurrentTargetOffsetTop + (eventY - mPrevY)/2;
                        updateContentOffsetTop((int) (offsetTop));
                        handled = true;
                    }
                    mPrevY = event.getY();
                }
                break;
            case MotionEvent.ACTION_UP:
            	if (mDownEvent != null && !mReturningToStart && !mRefreshing) {
	            	 mPulling = false;
	            	 final float eventY = event.getY();
	            	 float offsetTop = mCurrentTargetOffsetTop + eventY - mPrevY;
	                 updateContentOffsetTop((int) (offsetTop));
	            	 if (mDownEvent != null && !mReturningToStart) {
	                     if (mCurrentTargetOffsetTop - mOriginalOffsetTop  > mDistanceToTriggerSync) {
	                         // User movement passed distance; trigger a refresh
	                         startRefresh();
	                         handled = true;
	                         break;
	                     } else {
	                    	 mCancel.run();
	                    	 break;
	                     }
	            	 }
            	 }
            	break;
            case MotionEvent.ACTION_CANCEL:
            	mPulling = false;
                if (mDownEvent != null) {
                    mDownEvent.recycle();
                    mDownEvent = null;
                }
                mCancel.run();
                break;
        }
        return handled;
    }

    private void startRefresh() {
        removeCallbacks(mCancel);
        mOffsetToRefreshPosition.run();
        mRefreshing = true;
        mListener.onRefresh();
    }
    
    /**
     * 返回起始位置
     */
    public void onRefreshCompleted(){
    	mRefreshing = false;
    	mReturnToStartPosition.run();
    }
    

    private void updateContentOffsetTop(int targetTop) {
        final int currentTop = mTarget.getTop() + mRefreshIndicatorHeight;
        if (targetTop > mDistanceToTriggerSync*1.5) {
            targetTop = (int) (mDistanceToTriggerSync*1.5);
        } else if (targetTop < 0) {
            targetTop = 0;
        }
       
        setTargetOffsetTopAndBottom(targetTop - currentTop);
    }

    private void setTargetOffsetTopAndBottom(int offset) {
        mTarget.offsetTopAndBottom(offset);
        mCurrentTargetOffsetTop = mTarget.getTop()+mRefreshIndicatorHeight;
    }

    /**
     * Classes that wish to be notified when the swipe gesture correctly
     * triggers a refresh should implement this interface.
     */
    public interface OnRefreshListener {
        public void onRefresh();
        public void canRefresh();
        public void canNotRefresh();
    }

    /**
     * Simple AnimationListener to avoid having to implement unneeded methods in
     * AnimationListeners.
     */
    private class BaseAnimationListener implements AnimationListener {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }
    }

	/**
	 * @return the mRefreshIndicator
	 */
	public View getmRefreshIndicator() {
		return mRefreshIndicator;
	}

	/**
	 * @param mRefreshIndicator the mRefreshIndicator to set
	 */
	public void setmRefreshIndicator(View mRefreshIndicator) {
		this.mRefreshIndicator = mRefreshIndicator;
		ensureTarget();
	}
	
	/**
	 * 
	 * @package com.anewlives.zaishengzhan.control
	 * @author ESBJ_fuchao
	 * @description 指示器监听接口
	 */
	public interface IndicatorListener{
		
	}
}