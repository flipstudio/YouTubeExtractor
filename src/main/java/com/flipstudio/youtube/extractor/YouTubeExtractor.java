package com.flipstudio.youtube.extractor;

import android.net.Uri;
import android.os.*;
import android.os.Process;
import android.util.SparseArray;
import android.webkit.MimeTypeMap;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static java.util.Arrays.asList;

/**
 * Created by Pietro Caselani
 * On 06/03/14
 * YouTubeExtractor
 */
public final class YouTubeExtractor {
  //region Fields
  public static final int YOUTUBE_VIDEO_QUALITY_SMALL_240 = 36;
  public static final int YOUTUBE_VIDEO_QUALITY_MEDIUM_360 = 18;
  public static final int YOUTUBE_VIDEO_QUALITY_HD_720 = 22;
  public static final int YOUTUBE_VIDEO_QUALITY_HD_1080 = 37;
  private final String mVideoIdentifier;
  private final List<String> mElFields;
  private HttpsURLConnection mConnection;
  private List<Integer> mPreferredVideoQualities;
  private boolean mCancelled;
  //endregion

  //region Constructors
  public YouTubeExtractor(String videoIdentifier) {
    mVideoIdentifier = videoIdentifier;
    mElFields = new ArrayList<String>(asList("embedded", "detailpage", "vevo", ""));

    mPreferredVideoQualities = asList(YOUTUBE_VIDEO_QUALITY_MEDIUM_360,
        YOUTUBE_VIDEO_QUALITY_SMALL_240, YOUTUBE_VIDEO_QUALITY_HD_720,
        YOUTUBE_VIDEO_QUALITY_HD_1080);
  }
  //endregion

  //region Getters and Setters
  public List<Integer> getPreferredVideoQualities() {
    return mPreferredVideoQualities;
  }

  public void setPreferredVideoQualities(List<Integer> preferredVideoQualities) {
    mPreferredVideoQualities = preferredVideoQualities;
  }
  //endregion

  //region Public
  public void startExtracting(final YouTubeExtractorListener listener) {
    String elField = mElFields.get(0);
    mElFields.remove(0);
    if (elField.length() > 0) elField = "&el=" + elField;

    final String language = Locale.getDefault().getLanguage();

    final String link = String.format("https://www.youtube.com/get_video_info?video_id=%s%s&ps=default&eurl=&gl=US&hl=%s", mVideoIdentifier, elField, language);

		final HandlerThread youtubeExtractorThread = new HandlerThread("YouTubeExtractorThread", THREAD_PRIORITY_BACKGROUND);
		youtubeExtractorThread.start();

    final Handler youtubeExtractorHandler = new Handler(youtubeExtractorThread.getLooper());

		youtubeExtractorHandler.post(new Runnable() {
			@Override public void run() {
				try {
					mConnection = (HttpsURLConnection) new URL(link).openConnection();
					mConnection.setRequestProperty("Accept-Language", language);

					BufferedReader reader = new BufferedReader(new InputStreamReader(mConnection.getInputStream()));
					StringBuilder builder = new StringBuilder();
					String line;

					while ((line = reader.readLine()) != null && !mCancelled) builder.append(line);

					reader.close();

					if (!mCancelled) {
						final Uri uri = getYouTubeUri(builder.toString());

						youtubeExtractorHandler.post(new Runnable() {
							@Override public void run() {
								if (!mCancelled && listener != null) {
									listener.onSuccess(uri);
								}
							}
						});
					}
				} catch (final Exception e) {
					youtubeExtractorHandler.post(new Runnable() {
						@Override public void run() {
							if (!mCancelled && listener != null) {
								listener.onFailure(new Error(e));
							}
						}
					});
				} finally {
					if (mConnection != null) {
						mConnection.disconnect();
					}

					youtubeExtractorThread.quitSafely();
				}
			}
		});
  }

  public void cancelExtracting() {
    mCancelled = true;
  }
  //endregion

  //region Private
  private static HashMap<String, String> getQueryMap(String queryString, String charsetName)
      throws UnsupportedEncodingException {
    HashMap<String, String> map = new HashMap<String, String>();

    String[] fields = queryString.split("&");

    for (String field : fields) {
      String[] pair = field.split("=");
      if (pair.length == 2) {
        String key = pair[0];
        String value = URLDecoder.decode(pair[1], charsetName).replace('+', ' ');
        map.put(key, value);
      }
    }

    return map;
  }

  private Uri getYouTubeUri(String html)
      throws UnsupportedEncodingException, YouTubeExtractorException {
    HashMap<String, String> video = getQueryMap(html, "UTF-8");

    if (video.containsKey("url_encoded_fmt_stream_map")) {
      List<String> streamQueries = new ArrayList<String>(asList(video.get("url_encoded_fmt_stream_map").split(",")));

      String adaptiveFmts = video.get("adaptive_fmts");
      String[] split = adaptiveFmts.split(",");

      streamQueries.addAll(asList(split));

      SparseArray<String> streamLinks = new SparseArray<String>();
      for (String streamQuery : streamQueries) {
        HashMap<String, String> stream = getQueryMap(streamQuery, "UTF-8");
        String type = stream.get("type").split(";")[0];
        String urlString = stream.get("url");

        if (urlString != null && MimeTypeMap.getSingleton().hasMimeType(type)) {
          String signature = stream.get("sig");

          if (signature != null) {
            urlString = urlString + "&signature=" + signature;
          }

          if (getQueryMap(urlString, "UTF-8").containsKey("signature")) {
            streamLinks.put(Integer.parseInt(stream.get("itag")), urlString);
          }
        }
      }

      Uri videoUri = null;

      for (Integer videoQuality : mPreferredVideoQualities) {
        if (streamLinks.get(videoQuality, null) != null) {
          String streamLink = streamLinks.get(videoQuality);
          videoUri = Uri.parse(streamLink);
          break;
        }
      }

      return videoUri;
    } else {
      StringBuilder builder = new StringBuilder("Status: ")
          .append(video.get("status"))
          .append("\nReason: ")
          .append(video.get("reason"))
          .append("\nError code: ")
          .append(video.get("errorcode"));

      throw new YouTubeExtractorException(builder.toString());
    }
  }
  //endregion

  public class YouTubeExtractorException extends Exception {
    public YouTubeExtractorException(String detailMessage) {
      super(detailMessage);
    }
  }

  public interface YouTubeExtractorListener {
    public void onSuccess(Uri videoUri);
    public void onFailure(Error error);
  }
}