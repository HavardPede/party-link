package com.github.havardpede.partylink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class FakeHttpClient extends OkHttpClient {
	int responseCode = 200;
	String responseBody = "{\"commands\":[]}";
	boolean shouldFail = false;
	List<Request> capturedRequests = new ArrayList<>();

	@Override
	public Call newCall(Request request) {
		capturedRequests.add(request);
		return new FakeCall(request);
	}

	private class FakeCall implements Call {
		private final Request request;

		FakeCall(Request request) {
			this.request = request;
		}

		@Override
		public Response execute() throws IOException {
			if (shouldFail) {
				throw new IOException("Connection refused");
			}
			Response.Builder builder =
					new Response.Builder()
							.request(request)
							.protocol(Protocol.HTTP_1_1)
							.code(responseCode)
							.message("OK");
			if (responseBody != null) {
				builder.body(
						ResponseBody.create(MediaType.parse("application/json"), responseBody));
			}
			return builder.build();
		}

		@Override
		public Request request() {
			return request;
		}

		@Override
		public void enqueue(okhttp3.Callback callback) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void cancel() {}

		@Override
		public boolean isExecuted() {
			return false;
		}

		@Override
		public boolean isCanceled() {
			return false;
		}

		@Override
		public Call clone() {
			return new FakeCall(request);
		}

		@Override
		public okio.Timeout timeout() {
			return okio.Timeout.NONE;
		}
	}
}
