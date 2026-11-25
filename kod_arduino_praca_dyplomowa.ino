// Grzegorz Wróblewski 266860

// ====== A4988  ======
const uint8_t EN_PIN   = 3; 
const uint8_t STEP_PIN = 4;  
const uint8_t DIR_PIN  = 5;  

// ====== BLE ======
const unsigned long UART_BAUD = 115200;

const unsigned long CMD_GAP_MS = 60; 

volatile bool stopRequested = false;
unsigned int stepDelayUs = 800;  
bool holdAfterMove = false;      
long positionSteps = 0;

String rxBuf;
unsigned long lastRxMs = 0;

void blePrintln(const String &s) { Serial.println(s); }
void blePrintln(const char* s)   { Serial.println(s); }

void driverEnable(bool en) {
  digitalWrite(EN_PIN, en ? LOW : HIGH); 
}

void stepOnce() {
  digitalWrite(STEP_PIN, HIGH);
  delayMicroseconds(stepDelayUs);
  digitalWrite(STEP_PIN, LOW);
  delayMicroseconds(stepDelayUs);
}

void moveSteps(bool dirOut, long steps) {
  if (steps <= 0) return;

  stopRequested = false;
  digitalWrite(DIR_PIN, dirOut ? HIGH : LOW);
  driverEnable(true);

  for (long i = 0; i < steps; i++) {
    if (stopRequested) break;
    stepOnce();
    positionSteps += (dirOut ? 1 : -1);
  }

  if (!holdAfterMove) driverEnable(false);

  if (stopRequested) {
    blePrintln(String("ERR STOP POS ") + positionSteps);
  } else {
    blePrintln(String("DONE POS ") + positionSteps);
  }
}


void sendERR(const char* msg) { blePrintln(String("ERR ") + msg); }

String nextToken(String &s) {
  s.trim();
  int sp = s.indexOf(' ');
  if (sp < 0) { String t = s; s = ""; return t; }
  String t = s.substring(0, sp);
  s = s.substring(sp + 1);
  return t;
}

void handleCommand(String cmdLine) {
  cmdLine.trim();
  if (cmdLine.length() == 0) return;


  cmdLine.toUpperCase();

  String rest = cmdLine;
  String cmd = nextToken(rest);

  if (cmd == "PING") { blePrintln("PONG"); return; }

  if (cmd == "SPD") {
    long val = nextToken(rest).toInt();
    if (val < 200 || val > 20000) { sendERR("SPD_RANGE"); return; }
    stepDelayUs = (unsigned int)val;
     
    return;
  }

  if (cmd == "EN") {
    String v = nextToken(rest);
    if (v == "1") holdAfterMove = true;
    else if (v == "0") holdAfterMove = false;
    else { sendERR("EN_VALUE"); return; }
     
    return;
  }

  if (cmd == "STOP") {
    stopRequested = true;
     
    return;
  }

  if (cmd == "POS?") {
    blePrintln(String("POS ") + positionSteps);
    return;
  }

  if (cmd == "ZERO") {
    positionSteps = 0;
     
    return;
  }

  if (cmd == "IN" || cmd == "OUT") {
    long steps = nextToken(rest).toInt();
    if (steps <= 0 || steps > 200000) { sendERR("STEPS_RANGE"); return; }
    moveSteps(cmd == "OUT", steps);
    return;
  }

  sendERR("UNKNOWN");
}



void setup() {
  pinMode(EN_PIN, OUTPUT);
  digitalWrite(EN_PIN, HIGH);  

  pinMode(STEP_PIN, OUTPUT);
  pinMode(DIR_PIN, OUTPUT);
  digitalWrite(STEP_PIN, LOW);
  digitalWrite(DIR_PIN, LOW);

  Serial.begin(UART_BAUD);


  blePrintln("READY");
}




void loop() {
  while (Serial.available()) {
    char c = (char)Serial.read();

    if (c == '\r' || c == '\n' || c == '\0') continue;

    if (rxBuf.length() < 120) rxBuf += c;
    else rxBuf = "";

    lastRxMs = millis();
  }

  if (rxBuf.length() > 0 && (millis() - lastRxMs) > CMD_GAP_MS) {
    String cmd = rxBuf;
    rxBuf = "";
    handleCommand(cmd);
  }
}
