package org.wordpress.android;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.conn.HttpHostConnectException;
import org.wordpress.android.util.AlertUtil;
import org.wordpress.android.util.EscapeUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlrpc.android.ApiHelper;
import org.xmlrpc.android.XMLRPCClient;
import org.xmlrpc.android.XMLRPCException;
import org.xmlrpc.android.XMLRPCFault;

import vn.mbm.phimp.me.R;
import vn.mbm.phimp.me.database.AccountItem;
import vn.mbm.phimp.me.database.WordpressDBAdapter;
import vn.mbm.phimp.me.database.WordpressItem;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.URLUtil;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

public class AddAccount extends Activity {
	private EditText mUserNameEdit;
	private EditText mUrlEdit;
	private EditText mPasswordEdit;
	private String httpuser = "";
	private String httppassword = "";
	private Button mSettingsButton;
	private Button mCancelButton;
	private Button mSaveButton;
	public static Context ctx;
	private boolean wpcom = false;
	private String blogURL, xmlrpcURL,blogXmlrpcRUL;
	private static final String URL_WORDPRESS = "http://wordpress.com";	
	private ProgressDialog pd;
	private XMLRPCClient client;
	private int blogCtr = 0;
	private boolean isCustomURL = false;
	private ArrayList<CharSequence> aBlogNames = new ArrayList<CharSequence>();
	private ConnectivityManager mSystemService;
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		// TODO Auto-generated method stub
		super.onCreate(savedInstanceState);
		setContentView(R.layout.add_wordpress_account);	
		ctx = this;
		mSettingsButton = (Button) findViewById(R.id.btn_wp_add_account_setting);
		mCancelButton = (Button) findViewById(R.id.btn_wp_add_account_cancel);
		mSaveButton = (Button) findViewById(R.id.btn_wp_add_account_save);
		
		mUrlEdit = (EditText) findViewById(R.id.et_wp_add_account_url);
		mUserNameEdit = (EditText) findViewById(R.id.et_wp_add_account_username);
		mPasswordEdit = (EditText) findViewById(R.id.et_wp_add_account_password);
		
		mSystemService = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

		
		Bundle extras = getIntent().getExtras();
		if (extras != null) {
			wpcom = extras.getBoolean("wpcom", false);
			String username = extras.getString("username");
			if (username != null) {
				mUserNameEdit.setText(username);
			}
		}

		if (wpcom) {
			mUrlEdit.setVisibility(View.GONE);
		} else {
			ImageView logo = (ImageView) findViewById(R.id.wpcomLogo);
			logo.setImageDrawable(getResources().getDrawable(R.drawable.wplogo));
		}

		if (wpcom)
			mSettingsButton.setVisibility(View.GONE);
		else
			mSettingsButton.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					// TODO Auto-generated method stub
					Intent settings = new Intent(AddAccount.this, AddAcountSettings.class);
					settings.putExtra("httpuser", httpuser);
					settings.putExtra("httppassword", httppassword);
					startActivityForResult(settings, R.id.btn_wp_add_account_setting);
				}
			});
		
		mSaveButton.setOnClickListener(new OnClickListener() {			
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				if (mSystemService.getActiveNetworkInfo() == null) {
					AlertUtil.showAlert(AddAccount.this, R.string.no_network_title, R.string.no_network_message);
				} else {
					pd = ProgressDialog.show(AddAccount.this, getString(R.string.account_setup), getString(R.string.attempting_configure),
							true, false);

					Thread action = new Thread() {
						public void run() {
							Looper.prepare();
							configureAccount();
							Looper.loop();
						}
					};
					action.start();
				}
			}
		});
		
		mCancelButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				Bundle bundle = new Bundle();
				bundle.putString("returnStatus", "CANCEL");
				Intent mIntent = new Intent();
				mIntent.putExtras(bundle);
				setResult(RESULT_OK, mIntent);
				finish();
			}
		});
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case R.id.btn_wp_add_account_setting:
			if (resultCode == RESULT_OK) {
				Bundle extras = data.getExtras();
				httpuser = extras.getString("httpuser");
				httppassword = extras.getString("httppassword");
			}
			break;
		}
	}

	@Override
	public void onBackPressed() {
		Bundle bundle = new Bundle();
		bundle.putString("returnStatus", "CANCEL");
		Intent mIntent = new Intent();
		mIntent.putExtras(bundle);
		setResult(RESULT_OK, mIntent);
		finish();
	}

	private void configureAccount() {

		if (wpcom) {
			blogURL = URL_WORDPRESS;
		} else {
			blogURL = mUrlEdit.getText().toString().trim();
		}
		final String username = mUserNameEdit.getText().toString().trim();
		final String password = mPasswordEdit.getText().toString().trim();

		if (blogURL.equals("") || username.equals("") || password.equals("")) {
			pd.dismiss();
			AlertUtil.showAlert(AddAccount.this, R.string.required_fields, R.string.url_username_password_required);
			return;
		}

		// add http to the beginning of the URL if needed
		if (!(blogURL.toLowerCase().startsWith("http://")) && !(blogURL.toLowerCase().startsWith("https://"))) {
			blogURL = "http://" + blogURL; // default to http
		}

		if (!URLUtil.isValidUrl(blogURL)) {
			pd.dismiss();
			AlertUtil.showAlert(AddAccount.this, R.string.invalid_url, R.string.invalid_url_message);
			return;
		}

		// attempt to get the XMLRPC URL via RSD
		String rsdUrl = getRSDMetaTagHrefRegEx(blogURL);
		if (rsdUrl == null) {
			rsdUrl = getRSDMetaTagHref(blogURL);
		}

		if (rsdUrl != null) {
			xmlrpcURL = ApiHelper.getXMLRPCUrl(rsdUrl, false);
			if (xmlrpcURL == null)
				xmlrpcURL = rsdUrl.replace("?rsd", "");
		} else {
			isCustomURL = false;
			// try the user entered path
			try {
				client = new XMLRPCClient(blogURL, httpuser, httppassword);
				try {
					client.call("system.listMethods");
					xmlrpcURL = blogURL;
					isCustomURL = true;
				} catch (XMLRPCException e) {
					// guess the xmlrpc path
					String guessURL = blogURL;
					if (guessURL.substring(guessURL.length() - 1, guessURL.length()).equals("/")) {
						guessURL = guessURL.substring(0, guessURL.length() - 1);
					}
					guessURL += "/xmlrpc.php";
					client = new XMLRPCClient(guessURL, httpuser, httppassword);
					try {
						client.call("system.listMethods");
						xmlrpcURL = guessURL;
					} catch (XMLRPCException ex) {
					}
				}
			} catch (Exception e) {
			}
		}

		if (xmlrpcURL == null) {
			pd.dismiss();
			AlertUtil.showAlert(AddAccount.this, R.string.error, R.string.no_site_error);
		} else {
			// verify settings
			client = new XMLRPCClient(xmlrpcURL, httpuser, httppassword);

			XMLRPCMethod method = new XMLRPCMethod("wp.getUsersBlogs", new XMLRPCMethodCallback() {

				public void callFinished(Object[] result) {

					final String[] blogNames = new String[result.length];
					final String[] urls = new String[result.length];
					final String[] homeURLs = new String[result.length];
					final int[] blogIds = new int[result.length];
					final boolean[] wpcoms = new boolean[result.length];
					final String[] wpVersions = new String[result.length];
					HashMap<Object, Object> contentHash = new HashMap<Object, Object>();
					blogCtr = 0;
					// loop this!
					for (int ctr = 0; ctr < result.length; ctr++) {
						contentHash = (HashMap<Object, Object>) result[ctr];						
						
						String matchBlogName = contentHash.get("blogName").toString();
						if (matchBlogName.length() == 0) {
							matchBlogName = contentHash.get("url").toString();
						}

							blogNames[blogCtr] = matchBlogName;
							if (isCustomURL)
								urls[blogCtr] = blogURL;
							else
								urls[blogCtr] = contentHash.get("xmlrpc").toString();
							homeURLs[blogCtr] = contentHash.get("url").toString();
							blogIds[blogCtr] = Integer.parseInt(contentHash.get("blogid").toString());
							String blogURL = urls[blogCtr];
							blogXmlrpcRUL=blogURL;
							Log.e("Add account","blogXmlrpcRUL : "+blogXmlrpcRUL);
							aBlogNames.add(EscapeUtils.unescapeHtml(blogNames[blogCtr]));

							boolean wpcomFlag = false;
							// check for wordpress.com
							if (blogURL.toLowerCase().contains("wordpress.com")) {
								wpcomFlag = true;
							}
							wpcoms[blogCtr] = wpcomFlag;

							// attempt to get the software version
							String wpVersion = "";
							if (!wpcomFlag) {
								HashMap<String, String> hPost = new HashMap<String, String>();
								hPost.put("software_version", "software_version");
								Object[] vParams = { 1, username, password, hPost };
								Object versionResult = new Object();
								try {
									versionResult = (Object) client.call("wp.getOptions", vParams);
								} catch (XMLRPCException e) {
								}

								if (versionResult != null) {
									try {
										contentHash = (HashMap<Object, Object>) versionResult;
										HashMap<?, ?> sv = (HashMap<?, ?>) contentHash.get("software_version");
										wpVersion = sv.get("value").toString();
									} catch (Exception e) {
									}
								}
							} else {
								wpVersion = "3.4";
							}

							wpVersions[blogCtr] = wpVersion;

							blogCtr++;
					} // end loop
					pd.dismiss();
					Log.e("Danh", "blogCtr : "+blogCtr);
					if (blogCtr == 0) {
						String additionalText = "";
						if (result.length > 0) {
							additionalText = getString(R.string.additional);
						}
						AlertUtil.showAlert(AddAccount.this, R.string.no_blogs_found,
								String.format(getString(R.string.no_blogs_message), additionalText), getString(R.string.ok),
								new DialogInterface.OnClickListener() {
									public void onClick(DialogInterface dialog, int whichButton) {
										dialog.dismiss();
									}
								});
					} else {		
						
							//save database		        	
						long account_id = AccountItem.insertAccount(ctx, null, username, "wordpressdotcom", "1");
						if (account_id > 0)
						{
							WordpressDBAdapter wp=new WordpressDBAdapter(ctx);
							wp.open();
							if (WordpressItem.insertWordpressAccount(ctx,  String.valueOf(account_id), blogXmlrpcRUL, username, password, httpuser, httppassword,"wordpressdotcom"))
							{
								Toast.makeText(ctx, "Insert account '" + username + "' (wordpress.com) SUCCESS!", Toast.LENGTH_LONG).show();
								wp.close();
							}							
							else
							{
								Toast.makeText(ctx, "Insert account '" + username + "' (wordpress.com) FAIL!", Toast.LENGTH_LONG).show();
							}
						}

							Bundle bundle = new Bundle();
							bundle.putString("returnStatus", "SAVE");
							Intent mIntent = new Intent();
							mIntent.putExtras(bundle);
							setResult(RESULT_OK, mIntent);
							finish();
						}
					
				}
			});
			Object[] params = { username, password };

			method.call(params);
		}
	}

	interface XMLRPCMethodCallback {
		void callFinished(Object[] result);
	}

	class XMLRPCMethod extends Thread {
		private String method;
		private Object[] params;
		private Handler handler;
		private XMLRPCMethodCallback callBack;

		public XMLRPCMethod(String method, XMLRPCMethodCallback callBack) {
			this.method = method;
			this.callBack = callBack;

			handler = new Handler();

		}

		public void call() {
			call(null);
		}

		public void call(Object[] params) {
			this.params = params;
			start();
		}

		@Override
		public void run() {
			try {
				final Object[] result;
				result = (Object[]) client.call(method, params);
				handler.post(new Runnable() {
					public void run() {
						callBack.callFinished(result);
					}
				});
			} catch (final XMLRPCFault e) {
				handler.post(new Runnable() {
					public void run() {
						// e.printStackTrace();
						pd.dismiss();
						String message = e.getMessage();
						if (message.contains("code 403")) {
							// invalid login
							Thread shake = new Thread() {
								public void run() {
									Animation shake = AnimationUtils.loadAnimation(AddAccount.this, R.anim.shake);
									findViewById(R.id.section1).startAnimation(shake);
									Toast.makeText(AddAccount.this, getString(R.string.invalid_login), Toast.LENGTH_SHORT).show();
								}
							};
							runOnUiThread(shake);
						} else {
							AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(AddAccount.this);
							dialogBuilder.setTitle(getString(R.string.connection_error));
							if (message.contains("404"))
								message = getString(R.string.xmlrpc_error);
							dialogBuilder.setMessage(message);
							dialogBuilder.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int whichButton) {
									dialog.dismiss();
								}
							});
							dialogBuilder.setCancelable(true);
							dialogBuilder.create().show();
						}
					}
				});

			} catch (final XMLRPCException e) {

				handler.post(new Runnable() {
					public void run() {
						Throwable couse = e.getCause();
						e.printStackTrace();
						pd.dismiss();
						String message = e.getMessage();
						if (couse instanceof HttpHostConnectException) {

						} else {
							AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(AddAccount.this);
							dialogBuilder.setTitle(getString(R.string.connection_error));
							if (message.contains("404"))
								message = getString(R.string.xmlrpc_error);
							dialogBuilder.setMessage(message);
							dialogBuilder.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int whichButton) {
									dialog.dismiss();
								}
							});
							dialogBuilder.setCancelable(true);
							dialogBuilder.create().show();
						}
						e.printStackTrace();

					}
				});
			}
		}

	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		// ignore orientation change
		super.onConfigurationChanged(newConfig);
	}

	private static final Pattern rsdLink = Pattern.compile(
			"<link\\s*?rel=\"EditURI\"\\s*?type=\"application/rsd\\+xml\"\\s*?title=\"RSD\"\\s*?href=\"(.*?)\"\\s*?/>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private String getRSDMetaTagHrefRegEx(String urlString) {
		InputStream in = ApiHelper.getResponse(urlString);
		if (in != null) {
			try {
				String html = ApiHelper.convertStreamToString(in);
				Matcher matcher = rsdLink.matcher(html);
				if (matcher.find()) {
					String href = matcher.group(1);
					return href;
				}
			} catch (IOException e) {
				Log.e("wp_android", "IOEX", e);
				return null;
			}
		}
		return null;
	}

	private String getRSDMetaTagHref(String urlString) {
		// get the html code
		InputStream in = ApiHelper.getResponse(urlString);

		// parse the html and get the attribute for xmlrpc endpoint
		if (in != null) {
			XmlPullParser parser = Xml.newPullParser();
			try {
				// auto-detect the encoding from the stream
				parser.setInput(in, null);
				int eventType = parser.getEventType();
				while (eventType != XmlPullParser.END_DOCUMENT) {
					String name = null;
					String rel = "";
					String type = "";
					String href = "";
					switch (eventType) {
					case XmlPullParser.START_TAG:
						name = parser.getName();
						if (name.equalsIgnoreCase("link")) {
							for (int i = 0; i < parser.getAttributeCount(); i++) {
								String attrName = parser.getAttributeName(i);
								String attrValue = parser.getAttributeValue(i);
								if (attrName.equals("rel")) {
									rel = attrValue;
								} else if (attrName.equals("type"))
									type = attrValue;
								else if (attrName.equals("href"))
									href = attrValue;
							}

							if (rel.equals("EditURI") && type.equals("application/rsd+xml")) {
								return href;
							}
							// currentMessage.setLink(parser.nextText());
						}
						break;
					}
					eventType = parser.next();
				}
			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}

		}
		return null; // never found the rsd tag
	}

}
