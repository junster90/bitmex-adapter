package com.bookmap.plugins.layer0.bitmex.adapter;

import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;


import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import com.bookmap.plugins.layer0.bitmex.Provider;
import com.bookmap.plugins.layer0.bitmex.adapter.ConnectorUtils.GeneralType;
import com.bookmap.plugins.layer0.bitmex.adapter.ConnectorUtils.Method;
import com.bookmap.plugins.layer0.bitmex.adapter.ConnectorUtils.WebSocketOperation;

import velox.api.layer1.data.DisconnectionReason;
import velox.api.layer1.data.SubscribeInfo;

public class BmConnector implements Runnable {

	private boolean interruptionNeeded = false;
	private HttpClientHolder clientHolder;
	private String wssUrl;
	private String restApi;
	private HashMap<String, BmInstrument> activeBmInstrumentsMap = new HashMap<>();
	private CountDownLatch webSocketStartingLatch = new CountDownLatch(1);
	private CountDownLatch webSocketAuthLatch = new CountDownLatch(1);
	private ClientSocket socket;

	private JsonParser parser = new JsonParser();
	private boolean isReconnecting = false;
	private Provider provider;
	private TradeConnector tradeConnector;

	private ScheduledExecutorService executionsResetTimer;
	private ScheduledExecutorService positionRequestTimer;
	private int executionDay = 0;
	private boolean isExecutionReset;
	
	private Object socketLock = new Object();
	private int timerCount = 0;
	private boolean isInitiallyConnected;
	
	public BmConnector(HttpClientHolder clientHolder) {
        super();
        this.clientHolder = clientHolder;
    }

	public Provider getProvider() {
		return provider;
	}

	public void setProvider(Provider provider) {
		this.provider = provider;
	}

	public ClientSocket getSocket() {
		return socket;
	}

	public void setInterruptionNeeded(boolean interruptionNeeded) {
		this.interruptionNeeded = interruptionNeeded;
	}

	public String getRestApi() {
		return restApi;
	}

	public CountDownLatch getWebSocketAuthLatch() {
		return webSocketAuthLatch;
	}

	public TradeConnector getTradeConnector() {
		return tradeConnector;
	}

	public void setTradeConnector(TradeConnector tradeConnector) {
		this.tradeConnector = tradeConnector;
	}

	public CountDownLatch getWebSocketStartingLatch() {
		return webSocketStartingLatch;
	}

	private boolean isConnectionEstablished() {
		return clientHolder.makeRequest(GeneralType.BLANK, Method.GET, null) != null;
	}

	public void setWssUrl(String wssUrl) {
		this.wssUrl = wssUrl;
	}

	public void setRestApi(String restApi) {
		this.restApi = restApi;
	}

	public String wssAuthTwo() {
		String method = "GET";
		String subPath = "/realtime";
		String orderApiKey = tradeConnector.getOrderApiKey();
		String orderApiSecret = tradeConnector.getOrderApiSecret();
		long moment = ConnectorUtils.getMomentAndTimeToLive();
		
		LogBitmex.info("BmConnector wssAuthTwo() moment = " + moment);
		
		String res = null;
		String messageBody = ConnectorUtils.createMessageBody(method, subPath, null, moment);
		String signature = ConnectorUtils.generateSignature(orderApiSecret, messageBody);

		WsData wsData = new WsData(WebSocketOperation.AUTHKEY, orderApiKey, moment, signature);
		res = JsonParser.gson.toJson(wsData);

		return res;
	}

	public void wsConnect() {
		SslContextFactory ssl = new SslContextFactory();
		WebSocketClient client = new WebSocketClient(ssl);
		LogBitmex.info("[bitmex " + Constants.version + "] BmConnector wsConnect websocket starting...");

		try {
			LogBitmex.info("BmConnector wsConnect websocket being created...");
			socket = new ClientSocket();
			parser.setActiveInstrumentsMap(Collections.unmodifiableMap(activeBmInstrumentsMap));
			socket.setParser(parser);
			parser.setProvider(provider);

			LogBitmex.info("BmConnector wsConnect client starting...");

			client.start();
			URI echoUri = new URI(wssUrl);
			ClientUpgradeRequest request = new ClientUpgradeRequest();

			LogBitmex.info("BmConnector wsConnect websocket connecting..."); 
			client.connect(socket, echoUri, request);
			socket.getOpeningLatch().await();

			if (!provider.isCredentialsEmpty()) {// authentication needed
				LogBitmex.info("BmConnector wsConnect websocket auth...");
				String mes = wssAuthTwo();
				LogBitmex.info("BmConnector wsConnect websocket auth message passed");
				socket.sendMessage(mes);
				webSocketAuthLatch.await();
				WsData wsData = new WsData(WebSocketOperation.SUBSCRIBE,
						(Object[]) ConnectorUtils.getAuthenticatedTopicsList());
				String res = JsonParser.gson.toJson(wsData);
				socket.sendMessage(res);

				reportHistoricalExecutions("Filled");
				reportHistoricalExecutions("Canceled");
				
                
			}
            LogBitmex.info("Starting panel server...");
            provider.panelHelper.setEnabled(true);
            provider.panelHelper.startInputConnection();
            LogBitmex.info("Panel server started");

			webSocketStartingLatch.countDown();
			LogBitmex.info("BmConnector wsConnect websocket webSocketStartingLatch is down");

			if (isReconnecting) {
				provider.reportRestoredCoonection();

				for (BmInstrument instr : activeBmInstrumentsMap.values()) {
					if (instr.isSubscribed()) {
						subscribe(instr);
					}
				}
				isReconnecting = false;
			}

			// WAITING FOR THE SOCKET TO CLOSE
			
			CountDownLatch closingLatch = socket.getClosingLatch();
			closingLatch.await();
			isReconnecting = true;

		} catch (UpgradeException e) {
			LogBitmex.info("BmConnector wsConnect client cannot connect 0 ");
		} catch (UnresolvedAddressException e) {
			LogBitmex.info("BmConnector wsConnect client cannot connect 1 ");
		} catch (WebSocketException e) {
			LogBitmex.info("BmConnector wsConnect connection must be lost ");
		} catch (Exception e) {
			LogBitmex.info("BmConnector wsConnect an Exception thrown from the websocket", e);
			throw new RuntimeException(e);
		} finally {
			try {
				client.stop();
			} catch (Exception e) {
				LogBitmex.info("BmConnector wsConnect Got trouble stoppping client", e);
				throw new RuntimeException(e);
			}
		}
	}

	public void sendWebsocketMessage(String message) {
		try {
			getWebSocketStartingLatch().await();
		} catch (InterruptedException e) {
            LogBitmex.info("Sending message interrupted", e);
			throw new RuntimeException();
		}
		LogBitmex.info("BmConnector wsConnect Send websocket message");

		synchronized (socketLock) {
			if (socket != null) {
				socket.sendMessage(message);
			}
		}
	}

	public void fillActiveBmInstrumentsMap() {
		synchronized (activeBmInstrumentsMap) {
            String str = clientHolder.makeRequest(GeneralType.ACTIVE_INSTRUMENTS, Method.GET, null);
            if (str != null) {
                try {
                    BmInstrument[] instrs = JsonParser.getArrayFromJson(str, BmInstrument[].class);

                    for (BmInstrument instr : instrs) {
                        activeBmInstrumentsMap.put(instr.getSymbol(), instr);
                        provider.maxLeverages.put(instr.getSymbol(), (int) Math.round(1 / instr.getInitMargin()));
                    }
                } catch (Exception e) {
                    LogBitmex.info("", e);
                }
				activeBmInstrumentsMap.notify();
			}
		}
	}

	public HashMap<String, BmInstrument> getActiveInstrumentsMap() {
		return activeBmInstrumentsMap;
	}

	private void launchSnapshotTimer(BmInstrument instr) {
		int localTimerCount = timerCount;
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				Thread.currentThread().setName("-> BmConnector: snapshotTimer " + localTimerCount + " for" + instr.getSymbol() );
			
				if (socket == null || isReconnecting){
					LogBitmex.info("SnapshotTimer waiting for the socket, timer " + localTimerCount + " shutdown");
					return;
				}
				
				if (!instr.isOrderBookSnapshotParsed()) {
					LogBitmex.info("SnapshotTimer launchSnapshotTimer " + localTimerCount + " for " + instr.getSymbol() + ": resubscribe " + ZonedDateTime.now(ZoneOffset.UTC));
					unSubscribe(instr);
					
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {
					    LogBitmex.info("SnapshotTimer sleeping interrupted ", e);
					}
                    try {
                        LogBitmex.info("SnapshotTimer: subscribing ...");
                        subscribe(instr);
                    } catch (Exception e) {
                        LogBitmex.info("", e);
                    }
				} else {
					LogBitmex.info("BmConnector launchSnapshotTimer " + localTimerCount + " for " + instr.getSymbol() + ": end " + ZonedDateTime.now(ZoneOffset.UTC));
				}
			}
		};
		
		Timer previousTimer = instr.getSnapshotTimer();
		if (previousTimer != null) previousTimer.cancel();
		Timer timer = new Timer();
		instr.setSnapshotTimer(timer);
		LogBitmex.info("BmConnector launchSnapshotTimer " + localTimerCount + " for " + instr.getSymbol() + ": " + ZonedDateTime.now(ZoneOffset.UTC));
		timer.schedule(task, 10000);
		timerCount++;
	}

	private void launchExecutionsResetTimer() {
		class CustomThreadFactory implements ThreadFactory {
			@Override
            public Thread newThread(Runnable r) {
				return new Thread(r, "-> BmConnector: executionsResetTimer");
			}
		}

		ScheduledExecutorService executionsResetTimer = Executors
				.newSingleThreadScheduledExecutor(new CustomThreadFactory());
		this.executionsResetTimer = executionsResetTimer;
		LogBitmex.info("BmConnector launchExecutionsResetTimer(): ");
		executionsResetTimer.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				int dayNow = ZonedDateTime.now(ZoneOffset.UTC).getDayOfYear();

				if (executionDay < dayNow && !isExecutionReset) {
					executionDay = dayNow;

					Set<BmInstrument> instruments = new HashSet<>();
					synchronized (activeBmInstrumentsMap) {
						instruments.addAll(activeBmInstrumentsMap.values());
					}
					for (BmInstrument instrument : instruments) {
						instrument.setExecutionsVolume(0);
					}

					isExecutionReset = true;
				} else if (executionDay == dayNow) {
					isExecutionReset = false;
				}
			}
		}, 0, 1, TimeUnit.SECONDS);
	}
	
	private void startPositionRequestTimer() {
	    class CustomThreadFactory implements ThreadFactory {
	        @Override
	        public Thread newThread(Runnable r) {
	            return new Thread(r, "-> BmConnector: positionRequestTimer");
	        }
	    }
	    
	    ScheduledExecutorService positionRequestTimer = Executors
	            .newSingleThreadScheduledExecutor(new CustomThreadFactory());
	    this.positionRequestTimer = positionRequestTimer;

	    positionRequestTimer.scheduleWithFixedDelay(new Runnable() {
	        @Override
	        public void run() {
	            clientHolder.makeRequest(GeneralType.POSITION, Method.GET, null);
	        }
	    }, Constants.positionRequestDelaySeconds, Constants.positionRequestDelaySeconds, TimeUnit.SECONDS);
	}

	public void subscribe(BmInstrument instr) {
		LogBitmex.info("BmConnector subscribe: " + instr.getSymbol());
		instr.setSubscribed(true);
		LogBitmex.info("BmConnector subscribe: set true");

		sendWebsocketMessage(instr.getSubscribeReq());
		launchSnapshotTimer(instr);

		if (!provider.isCredentialsEmpty()) {// if authenticated
			instr.setExecutionsVolume(countExecutionsVolume(instr.getSymbol()));
		}
	}

	public void unSubscribe(BmInstrument instr) {
        try {
            sendWebsocketMessage(instr.getUnSubscribeReq());
        } catch (Exception e) {
            LogBitmex.info("", e);
        }
		
		Timer timer = instr.getSnapshotTimer();
		if (timer != null) {
			timer.cancel();
			LogBitmex.info("BmConnector unSubscribe: timer gets cancelled");
			}
		instr.setSubscribed(false);
		
	}

    private int countExecutionsVolume(String symbol) {
        String dataADayBefore = ConnectorUtils.getDateTwentyFourHoursAgoAsUrlEncodedString();
        StringBuilder sb = new StringBuilder();
        sb.append("?symbol=").append(symbol)
                .append("&filter=%7B%22ordStatus%22%3A%22Filled%22%7D&count=100&reverse=false&startTime=")
                .append(dataADayBefore);

        String addr = sb.toString();
        String result = tradeConnector.require(GeneralType.EXECUTION, Method.GET, null, false, addr);
        
        try {
            UnitOrder[] orders = JsonParser.getArrayFromJson(result, UnitOrder[].class);
            int sum = 0;

            if (orders != null && orders.length > 0) {
                for (UnitOrder order : orders) {
                    sum += order.getOrderQty();
                }
            }
            return sum;
        } catch (NullPointerException e) {
            LogBitmex.info("", e);
            return 0;
        }
    }

	private void reportHistoricalExecutions(String ordStatus) {
		// private void reportHistoricalExecutions(String symbol, String
		// ordStatus) {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		ZonedDateTime startTime = now.minusDays(0)
				.minusHours(now.getHour())
				.minusMinutes(now.getMinute())
				.minusSeconds(now.getSecond())
				.minusNanos(now.getNano());

		List<UnitExecution> historicalExecutions = new LinkedList<>();
		for (int i = 0;; i += 500) {
			StringBuilder sb = new StringBuilder();
			sb.append("?filter=%7B%22ordStatus%22%3A%20%22")
            .append(ordStatus).append("%22%7D&count=500&reverse=true&startTime=")
            .append(startTime).append("&endTime=").append(now)
            .append("&start=").append(i);

	        String address = sb.toString();
	        String response = tradeConnector.require(GeneralType.EXECUTION, Method.GET, null, false, address);

			UnitExecution[] executions = JsonParser.getArrayFromJson(response,
					UnitExecution[].class);

			if (executions == null || executions.length == 0) {
				break;
			} else {
				for (int k = 0, n = executions.length; k < n; k++) {
					if (!executions[k].getExecType().equals("Funding")) {
						historicalExecutions.add(executions[k]);
					}
				}
			}
		}
		LogBitmex.info("BmConnector report " + ordStatus + ": listSize =  " + historicalExecutions.size());

		if (historicalExecutions != null && historicalExecutions.size() > 0) {
			provider.updateExecutionsHistory(
					historicalExecutions.toArray(new UnitExecution[historicalExecutions.size()]));
		}
	}

	@Override
	public void run() {
		while (!interruptionNeeded) {

            if (!isConnectionEstablished()) {
                if (!isInitiallyConnected) {
                    provider.adminListeners
                            .forEach(l -> l.onConnectionLost(DisconnectionReason.FATAL, "No connection with BitMEX"));
                    interruptionNeeded = true;
                } else {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        LogBitmex.info("", e);
                        throw new RuntimeException();
                    }
                }
                continue;
            }
            isInitiallyConnected = true;
            
			if (activeBmInstrumentsMap.isEmpty()) {
				fillActiveBmInstrumentsMap();

				CopyOnWriteArrayList<SubscribeInfo> knownInstruments = new CopyOnWriteArrayList<>();
				for (BmInstrument instrument : activeBmInstrumentsMap.values()) {
						knownInstruments.add(new SubscribeInfo(instrument.getSymbol(), null, null));
				}
				provider.setKnownInstruments(knownInstruments);

				launchExecutionsResetTimer();
				if (!provider.isCredentialsEmpty()) {
				    startPositionRequestTimer();
				}
				if (activeBmInstrumentsMap.isEmpty()) {
					continue;
				}
			}
			if (!interruptionNeeded) {
				wsConnect();
			}
			if (!interruptionNeeded) {
	            provider.panelHelper.stop();
				provider.reportLostConnection();
			}

		}
        if (executionsResetTimer != null) {
            executionsResetTimer.shutdownNow();
        }
		closeSocket();

        if (positionRequestTimer != null) {
            positionRequestTimer.shutdownNow();
        }
		LogBitmex.info("BmConnector run: closing");
	}
	
	public void closeSocket(){
		synchronized (socketLock) {
			if (socket != null) {
				socket.close();
			}
		}
	}
	
	public int getMaximumLeverage(String alias) {
	    double initMarginReq = getActiveInstrumentsMap().get(alias).getInitMargin();
        return (int) Math.round(1/initMarginReq);
	}
}
