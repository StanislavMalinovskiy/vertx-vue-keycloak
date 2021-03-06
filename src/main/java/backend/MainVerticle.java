package backend;

import java.net.URI;
import java.net.URISyntaxException;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2FlowType;
import io.vertx.ext.auth.oauth2.providers.KeycloakAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;

public class MainVerticle extends AbstractVerticle {

	// configuration for HTTP server
	private JsonObject httpServer = new JsonObject()
			.put("hostname", "0.0.0.0")
			.put("port", 8080);

	// configuration for HTTPS server
	private JsonObject httpsServer = new JsonObject()
			.put("hostname", "0.0.0.0")
			.put("port", 4443)
			.put("keyStore", "test.jks")
			.put("enforceRedirect", false);

	private JsonArray messages = new JsonArray()
			.add(new JsonObject().put("content", "blabla"))
			.add(new JsonObject().put("content", "here's a second message"))
			.add(new JsonObject().put("content", "one more"));

	@Override
	public void start(Future<Void> startFuture) {
		createHttpServerAndRoutes();
		createApiEndpoints();
	}


	@Override
	public void stop() throws Exception {
		super.stop();
	}


	private void createHttpServerAndRoutes()	{
		Router router = Router.router(vertx);

		// create HTTP server
		HttpServerOptions httpOptions = new HttpServerOptions();
		httpOptions.setHost(httpServer.getString("hostname"));
		httpOptions.setPort(httpServer.getInteger("port"));
		httpOptions.setSsl(false);
		vertx.createHttpServer(httpOptions).requestHandler(router::accept).listen();
		System.out.println("created HTTP server at " + httpServer.getString("hostname") + ":" + httpServer.getInteger("port"));

		// create HTTPS server 
		HttpServerOptions httpsOptions = new HttpServerOptions();
		httpsOptions.setHost(httpsServer.getString("hostname"));
		httpsOptions.setPort(httpsServer.getInteger("port"));
		httpsOptions.setSsl(true);
		httpsOptions.setKeyStoreOptions(new JksOptions().setPath( httpsServer.getString("keyStore") ).setPassword("testpassword"));
		vertx.createHttpServer(httpsOptions).requestHandler(router::accept).listen();
		System.out.println("created HTTPS server at " + httpsServer.getString("hostname") + ":" + httpsServer.getInteger("port") + " (keyFile: " + httpsServer.getString("keyStore") + ")");

		// configure BodyHandler
		final BodyHandler bodyHandler = BodyHandler.create();
		router.route("/app/*").handler(bodyHandler);
		router.route("/login").handler(bodyHandler);
		router.route().handler(BodyHandler.create());

		// enable CORS
		router.route().handler(CorsHandler.create("http://localhost:8081")
				.allowedMethod(io.vertx.core.http.HttpMethod.GET)
				.allowedMethod(io.vertx.core.http.HttpMethod.POST)
				.allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
				.allowCredentials(true)
				.allowedHeader("Access-Control-Allow-Headers")
				.allowedHeader("Authorization")
				.allowedHeader("Access-Control-Allow-Method")
				.allowedHeader("Access-Control-Allow-Origin")
				.allowedHeader("Access-Control-Allow-Credentials")
				.allowedHeader("Content-Type"));

		// HTTP to HTTPS redirect
		router.route().handler( context -> {
			boolean sslUsed = context.request().isSSL();
			boolean enforceSslRedirect = httpsServer.getBoolean("enforceRedirect");

			if(!sslUsed && enforceSslRedirect) {
				try {
					int httpsPort = httpsServer.getInteger("port");

					URI myHttpUri = new URI( context.request().absoluteURI() );
					URI myHttpsUri = new URI("https", 
							myHttpUri.getUserInfo(), 
							myHttpUri.getHost(), 
							httpsPort,
							myHttpUri.getRawPath(), 
							myHttpUri.getRawQuery(), 
							myHttpUri.getRawFragment());
					context.response().putHeader("location", myHttpsUri.toString() ).setStatusCode(302).end();
				} catch(URISyntaxException ex) {
					ex.printStackTrace();
					context.next();
				}
			}
			else context.next();
		});

		// configure OAuth2 based on Keycloak
		JsonObject keycloakJson = new JsonObject()
				.put("realm", "master") // (1)
				.put("realm-public-key", "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqZeGGDeEHmmUN4/UXh2gQD0yZEZirprsrdYK7GfcE1+QF9yfYfBrIv5cQUssFQKISVpbbLcoqYolsxcOvDyVFSQedHRsumOzqNZK38RHkidPMPrSNof5C3iMIHuXOCv/6exnLZvVoeYmkq42davYEz1tpSWzkZnlUMbRZFs1CfzLMM2rsAJWsO1/5zbDm0JhFl7EFUsTki72ihac1Q5zUUSFyf1jKUEkL7rrkYINjgAaQKktE8pnubc3Y44F5llY4YyU9/bqUWqMYDx868oiDcnoBpGGd4QrUMlbULZZLRqqUKK6iG1kHxDCJQ9gaCiJoELyAqXjnnO28OODQhxMHQIDAQAB") // (2)
				.put("auth-server-url", "http://127.0.0.1:38080/auth")
				.put("ssl-required", "external")
				.put("resource", "vertx-account") // (3)
				.put("credentials", new JsonObject().put("secret", "0c22e587-2ccb-4dd3-b017-5ff6a903891b")); // (4)
		OAuth2Auth oauth2 = KeycloakAuth.create(vertx, OAuth2FlowType.PASSWORD, keycloakJson);


//		// handler to deliver the user info object, currently disabled
//		router.route("/app/userinfo").handler(context -> {
//			if (context.user() != null) {
//				JsonObject userDetails = context.user().principal();
//				userDetails.remove("password");
//				userDetails.put("jsessionid", context.session().id());
//				context.request().response().putHeader("Content-Type", "application/json");
//				context.request().response().end(userDetails.encodePrettily());
//			}
//			else context.request().response().end(
//					new JsonObject().put("error", "401").put("message", "user is not authenticated").encodePrettily()
//					);
//		});

		// Login handler
		router.post("/login").produces("application/json").handler(rc -> {
			System.err.println("received body ::: '"+rc.getBodyAsString()+"'");
			JsonObject userJson = rc.getBodyAsJson();
			System.err.println("User ::: "+userJson.encode());

			oauth2.authenticate(userJson, res -> {
				if (res.failed()) {
					System.err.println("Access token error: {} " + res.cause().getMessage());
					rc.response().setStatusCode(HttpResponseStatus.UNAUTHORIZED.code()).end();
				} else {
					User user = res.result();
					System.out.println("Success: we have found user: "+user.principal().encodePrettily());
					rc.response().end(user.principal().encodePrettily());
				}
			});
		});

		// router.post("/login").handler(FormLoginHandler.create(authProvider).setDirectLoggedInOKURL("/app/"));
		//
		//		// Implement logout
		//		router.route("/logout").handler(context -> {
		//			context.clearUser();
		//			context.setUser(null);
		//			context.session().destroy();
		//			context.setSession(null);
		//			System.out.println("session destroyed, should log out now");
		////			context.reroute("/");
		//		});

		// make sure the user is properly authenticated when using the eventbus, if not reject with 403
		// NOTE: this is currently disabled, as the user auth needs to be properly synchronized between the Frontend and the backend
		//		router.route("/eventbus/*").handler(ctx -> {
		//			// we need to be logged in
		//			if (ctx.user() == null) {
		//				ctx.fail(403);
		//			} else {
		//				ctx.next();
		//			}
		//		});

		// configure EventBus based on the SockJSBridge
		router.route("/eventbus/*").handler(new SockJSBridge(vertx));

		// staticHandler to handle static resources
		router.route().handler(StaticHandler.create().setCachingEnabled(true));
	}


	/**
	 * creates Vertx EventBus based API endpoints (for query and mutation)
	 */
	private void createApiEndpoints() {
		vertx.eventBus().consumer("/api/messages", this::apiMessages);
		vertx.eventBus().consumer("/api/messages/delete", this::apiMessagesDelete);
		vertx.eventBus().consumer("/api/messages/add", this::apiMessagesAdd);
	}

	/**
	 * get messages API handler, this simply returns our as-is messages array
	 * @param msg
	 */
	private void apiMessages(Message<JsonObject> msg) {
		System.err.println("apiMessages called");
		msg.reply(messages);
	}

	/**
	 * delete message API handler, this deletes a given message from our messages array and 
	 * publishes the entire message array to all potential subscribers
	 * @param msg
	 */
	private void apiMessagesDelete(Message<JsonObject> msg) {
		JsonObject inputObject = msg.body();
		System.err.println("apiMessagesDelete called : "+inputObject.encode());

		for(int a=0; a < messages.size(); a++) {
			if(messages.getJsonObject(a).equals(inputObject)) {
				System.out.println("=> removing document: "+inputObject.encode());
				messages.remove(a);
				break;
			}
		}
		// publish all known messages to any subscriber
		this.vertx.eventBus().publish(":pubsub/messages", messages);
		msg.reply(new JsonObject());
	}

	/**
	 * add message API handler, this adds a new message into our messages array and 
	 * publishes the entire message array to all potential subscribers
	 * @param msg
	 */
	private void apiMessagesAdd(Message<JsonObject> msg) {
		JsonObject inputObject = msg.body();
		System.err.println("apiMessagesAdd called : "+inputObject.encode());
		messages.add(inputObject);

		// publish all known messages to any subscriber
		this.vertx.eventBus().publish(":pubsub/messages", messages);
		msg.reply(new JsonObject());
	}
}

