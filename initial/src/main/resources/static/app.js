var stompClient = null;

function setConnected(connected) {
  $("#connect").prop("disabled", connected);
  $("#disconnect").prop("disabled", !connected);
  if (connected) {
    $("#conversation").show();
  } else {
    $("#conversation").hide();
  }
  $("#fileContent").html("");
}

function connect() {
  var socket = new SockJS("/gs-guide-websocket");
  stompClient = Stomp.over(socket);
  stompClient.connect({}, function (frame) {
    setConnected(true);
    console.log("Connected: " + frame);
    stompClient.subscribe("/topic/fileContent", function (message) {
      showFileContent(JSON.parse(message.body));
    });
    stompClient.subscribe("/topic/fileUpdate", function (message) {
      appendFileContent(JSON.parse(message.body));
    });
  });
}

function disconnect() {
  if (stompClient !== null) {
    stompClient.disconnect();
  }
  setConnected(false);
  console.log("Disconnected");
}

function readFile() {
  var filePath = $("#filePath").val();
  var userId = $("#userId").val(); // 사용자 ID 입력값 가져오기

  stompClient.send(
    "/app/readFile",
    {},
    JSON.stringify({
      filePath: filePath,
      userId: userId, // 사용자 ID 포함
    })
  );
}

function showFileContent(message) {
  $("#fileContent").html("");
  for (var i = 0; i < message.length; i++) {
    $("#fileContent").append("<tr><td>" + message[i] + "</td></tr>");
  }
}

function appendFileContent(message) {
  for (var i = 0; i < message.length; i++) {
    $("#fileContent").append("<tr><td>" + message[i] + "</td></tr>");
  }
}

$(function () {
  $("form").on("submit", function (e) {
    e.preventDefault();
  });
  $("#connect").click(function () {
    connect();
  });
  $("#disconnect").click(function () {
    disconnect();
  });
  $("#send").click(function () {
    readFile();
  });
});
