package com.yidan.pullableswiperefresh;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.support.design.widget.TabLayout;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.NestedScrollView;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;

import com.yidan.pullableswiperefreshlibrary.ExSwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private ExSwipeRefreshLayout scrollView;
    private TabLayout tabLayout;
    private ViewPager viewPager;

    private final int DISTANCE_TO_TRIGGER_SYNC = 120;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        scrollView = (ExSwipeRefreshLayout) findViewById(R.id.scroll_view);
        scrollView.setMode(ExSwipeRefreshLayout.Mode.BOTH);
        scrollView.setDistanceToTriggerSync(DISTANCE_TO_TRIGGER_SYNC);
        final TextView footerText = new TextView(this);
        footerText.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        footerText.setGravity(Gravity.CENTER);
        scrollView.setFooterView(footerText);
        scrollView.setOnPullFromStartListener(new ExSwipeRefreshLayout.OnPullFromStartListener() {
            @Override
            public void onRefresh() {
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        scrollView.setRefreshing(false);
                    }
                }, 1000);

            }

            @Override
            public void onPullDistance(int distance) {

            }

            @Override
            public void onPullEnable(boolean enable) {

            }
        });
        scrollView.setOnPullFromEndListener(new ExSwipeRefreshLayout.OnPullFromEndListener() {
            boolean isLoading;

            @Override
            public void onLoadMore() {
                isLoading = true;
                footerText.setText("Loading...");
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        scrollView.stopLoadingMore();
                        isLoading = false;
                    }
                }, 1000);

            }

            @Override
            public void onPushDistance(int distance) {
                if (!isLoading) {
                    footerText.setText(distance > DISTANCE_TO_TRIGGER_SYNC ? "Already reached" : "Almost done");
                }

            }

            @Override
            public void onPushEnable(boolean enable) {

            }
        });

        tabLayout = (TabLayout) findViewById(R.id.tab_layout);
        viewPager = (ViewPager) findViewById(R.id.view_pager);

        List<String> urls = new ArrayList<>();
        urls.add("https://github.com/chrisbanes/Android-PullToRefresh");
        urls.add("https://github.com/gumuxiansheng");
        urls.add("http://www.jrtstudio.com/sites/default/files/ico_android.png");

        final DetailImagesAdapter adapter = new DetailImagesAdapter(this, urls, scrollView);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(3);

        tabLayout.setupWithViewPager(viewPager);
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout) {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                // change the nested scroll view
                scrollView.setNestedScrollView(adapter.mNestedScrollViews.get(position));
            }
        });
        viewPager.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                viewPager.measure(viewPager.getLayoutParams().width, viewPager.getLayoutParams().height);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {

            }
        });

    }

    public static class DetailImagesAdapter extends PagerAdapter {

        private List<String> mUrls;
        private Context mContext;
        private SparseArray<View> mNestedScrollViews = new SparseArray<>();

        private ExSwipeRefreshLayout mSwipeRefreshLayout;

        public DetailImagesAdapter(Context context, List<String> urls, ExSwipeRefreshLayout swipeRefreshLayout) {
            this.mContext = context;
            this.mUrls = urls;
            this.mSwipeRefreshLayout = swipeRefreshLayout;

        }

        @Override
        public int getCount() {
            return mUrls.size();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return "TAB" + position;
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == (View) object;
        }

        @Override
        public Object instantiateItem(ViewGroup container, final int position) {
            Log.d("DetailXXXXXX", "instantiateItem");
            View contentView = LayoutInflater.from(mContext).inflate(R.layout.layout_web_info, null, false);
            NestedScrollView nestedScrollView = (NestedScrollView) contentView.findViewById(R.id.nested_scroll_view);
            mNestedScrollViews.put(position, nestedScrollView);
            if (mNestedScrollViews.size() == 1) {
                mSwipeRefreshLayout.setNestedScrollView(nestedScrollView);
            }
            WebView webView = (WebView) contentView.findViewById(R.id.web_view);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDatabaseEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.loadUrl(mUrls.get(position));
            ((ViewPager) container).addView(contentView);
            return contentView;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }

    }
}
