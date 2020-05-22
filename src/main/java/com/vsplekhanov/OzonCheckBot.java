package com.vsplekhanov;

import me.ramswaroop.jbot.core.common.Controller;
import me.ramswaroop.jbot.core.common.EventType;
import me.ramswaroop.jbot.core.common.JBot;
import me.ramswaroop.jbot.core.slack.Bot;
import me.ramswaroop.jbot.core.slack.models.Event;
import org.openqa.selenium.By;
import org.openqa.selenium.InvalidElementStateException;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@JBot
public class OzonCheckBot extends Bot {
    private static final String START = "старт";
    private static final String TEST_START = "test";
    private static final String STOP = "стоп";
    private static final String HEADLESS_OFF = "показать";
    private static final String HEADLESS_ON = "скрыть";
    private static final String PASS = "********";
    private static final String LOGIN = "***********";
    private static final int MILLIS_TO_SLEEP = 5000;
    private static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static Logger log = LoggerFactory.getLogger(OzonCheckBot.class);

    private volatile boolean headless = true;
    private String orderId;
    private String startDate;
    private int count;
    private int period;
    private List<String> dates;
    private volatile boolean running;

    private void checkExit(WebSocketSession session, Event event) {
        if (STOP.equals(event.getText())) {
            reply(session, event, "Завершаю работу. Пока!");
            log.debug("Stopped by user");
            System.exit(0);
        }
    }


    @Controller(pattern = "\\?", events = EventType.DIRECT_MESSAGE)
    public void help(WebSocketSession session, Event event) {
        checkExit(session, event);
        String message = "Привет, я могу проверять доступность доставок для Ozon по номеру заказа и диапазону дат."
                + "\nДоступные команды: "
                + "\n" + START + " - Начать новую проверку"
                + "\n" + STOP + " - Завершить работу программы"
                + "\n" + HEADLESS_OFF + " - Показать работу браузера (по умолчанию скрыта)"
                + "\n" + HEADLESS_ON + " - Скрыть работу браузера (по умолчанию скрыта)"
                + "\n? - Показать доступные команды";

        reply(session, event, message);
    }


    @Controller(pattern = TEST_START, events = EventType.DIRECT_MESSAGE)
    public void test(WebSocketSession session, Event event) {
        checkExit(session, event);
        log.debug("Test");
        orderId = "43902764";
        LocalDate date1 = LocalDate.parse("25.06.2020", FORMATTER);
        count = 1;
        running = true;
        dates = IntStream.iterate(0, i -> i + 1)
                .limit(15)
                .mapToObj(date1::plusDays)
                .map(date -> date.format(FORMATTER))
                .collect(Collectors.toList());
        startCheckLoop(session, event);

    }

    @Controller(pattern = HEADLESS_ON, events = EventType.DIRECT_MESSAGE)
    public void hide(WebSocketSession session, Event event) {
        checkExit(session, event);
        headless = true;
        log.debug("headless on");
        reply(session, event, "Скрытный режим активирован");
    }

    @Controller(pattern = HEADLESS_OFF, events = EventType.DIRECT_MESSAGE)
    public void show(WebSocketSession session, Event event) {
        checkExit(session, event);
        headless = false;
        log.debug("headless off");
        reply(session, event, "Скрытный режим выключен");
    }

    @Controller(events = EventType.DIRECT_MESSAGE)
    public void wrongMessage(WebSocketSession session, Event event) {
        checkExit(session, event);
        reply(session, event, "Что-то не то, может опечатка?");
    }

    @Controller(pattern = START, next = "getOrderId", events = EventType.DIRECT_MESSAGE)
    public void start(WebSocketSession session, Event event) {
        checkExit(session, event);
        if (running) {
            log.debug("Trying to start second thread");
            reply(session, event, "Я не могу проверять 2 заказа одновременно");
            stopConversation(event);
        } else {
            running = true;
            startConversation(event, "getOrderId");   // start conversation
            log.debug("Started by user");
            reply(session, event, "Привет, какой номер заказа нужно проверить?");
        }
    }


    @Controller(next = "getStartDate", events = EventType.DIRECT_MESSAGE, pattern = "\\d+")
    public void getOrderId(WebSocketSession session, Event event) {
        checkExit(session, event);
        if (event.getText().matches("\\d+$")) {
            orderId = event.getText().trim();
            reply(session, event, "Отлично, с какого числа начать проверять? (через точку, например 01.01.2021)");
            log.debug("Get order id: " + orderId);
            nextConversation(event);
        } else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getOrderId");
        }
    }

    @Controller(next = "getEndDate", events = EventType.DIRECT_MESSAGE, pattern = "\\d{2}.\\d{2}.\\d{4}$")
    public void getStartDate(WebSocketSession session, Event event) {
        checkExit(session, event);
        if (event.getText().matches("\\d{2}.\\d{2}.\\d{4}$")) {
            reply(session, event, "Хорошо, до какого числа проверять? (максимум 90 дней)");
            startDate = event.getText().trim();
            log.debug("Get start date: " + startDate);
            nextConversation(event);
        } else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getStartDate");
        }

    }

    @Controller(next = "getCount", events = EventType.DIRECT_MESSAGE, pattern = "\\d{2}.\\d{2}.\\d{4}$")
    public void getEndDate(WebSocketSession session, Event event) {
        checkExit(session, event);
        if (event.getText().matches("\\d{2}.\\d{2}.\\d{4}$")) {
            String endDate = event.getText().trim();

            LocalDate date1 = LocalDate.parse(startDate, FORMATTER);
            LocalDate date2 = LocalDate.parse(endDate, FORMATTER);

            long duration = ChronoUnit.DAYS.between(date1, date2);
            if (duration < 1) {
                reply(session, event, "Неправильный порядок дат, введи первую дату еще раз");
                nextConversation("getStartDate");
                return;
            }
            log.debug("Get end date: " + endDate);
            log.debug("Duration: " + duration);
            reply(session, event, "Выбран период в " + duration + " дней.\nСколько раз повторять проверку?");
            dates = IntStream.iterate(0, i -> i + 1)
                    .limit(duration + 1)
                    .mapToObj(date1::plusDays)
                    .map(date -> date.format(FORMATTER))
                    .collect(Collectors.toList());

            nextConversation(event);
        } else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getEndDate");
        }
    }

    @Controller(next = "getPeriod", events = EventType.DIRECT_MESSAGE, pattern = "\\d+$")
    public void getCount(WebSocketSession session, Event event) {
        checkExit(session, event);

        if (event.getText().matches("\\d+$")) {
            count = Integer.parseInt(event.getText().trim());
            if (count == 1) {
                startCheckLoop(session, event);
            } else if (count < 0) {
                reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
                nextConversation("getCount");
            } else {
                log.debug("get count: " + count);
                reply(session, event, "Принято, как часто проверять? (сколько минут между проверками?)");
                nextConversation(event);
            }
        } else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getCount");
        }
    }

    private void startCheckLoop(WebSocketSession session, Event event) {
        reply(session, event, "Окей, начинаю проверку.\nЧтобы завершить напиши \"стоп\"");
        log.debug("Start check");
        for (int i = 0; i < count; i++) {
            startCheck(session, event);

            if (i > count - 1) {
                try {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(period));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        running = false;
        reply(session, event, "Проверка закончена, чтобы начать новую напиши \"старт\"");
        stopConversation(event);
    }

    @Controller(events = EventType.DIRECT_MESSAGE, pattern = "\\d+$")
    public void getPeriod(WebSocketSession session, Event event) {
        checkExit(session, event);

        if (event.getText().matches("\\d+$")) {
            period = Integer.parseInt(event.getText().trim());
            startCheckLoop(session, event);
        } else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getPeriod");
        }
    }

    @Controller(pattern = STOP, next = "getOrderId", events = EventType.DIRECT_MESSAGE)
    public void stop(WebSocketSession session, Event event) {
        checkExit(session, event);
    }

    private void startCheck(WebSocketSession session, Event event) {

        WebDriver driver = null;
        try {
            if (headless) {
                ChromeOptions options = new ChromeOptions();
                options.addArguments("--headless");
                driver = new ChromeDriver(options);
            } else {
                driver = new ChromeDriver();
            }
            log.debug("Driver is created");

            driver.get("https://seller.ozon.ru/signin");
            WebElement userName = driver.findElement(By.name("userName"));
            userName.sendKeys(LOGIN);
            WebElement password = driver.findElement(By.name("password"));
            password.sendKeys(PASS);
            password.sendKeys(Keys.ENTER);
            log.debug("Logged in");

            doCheck(session, event, driver);
        } finally {
            if (driver != null) {
                driver.close();
            }
        }
    }

    private void doCheck(WebSocketSession session, Event event, WebDriver driver) {
        try {
            String targetUrl = "https://seller.ozon.ru/supply/orders?tab=approved";
            while (!targetUrl.equals(driver.getCurrentUrl())) {
                waitCoupleOfSec(1);
                driver.get(targetUrl);
            }


            List<String> foundDates = new ArrayList<>();
            List<WebElement> elements = new ArrayList<>();

            while (elements.isEmpty()) {
                waitCoupleOfSec(5);
                elements = driver.findElements(By.xpath("/html/body/div/div/main/div/div/div/div/div/div/div/div/div/div/div/div/table/tbody/tr"));
            }

            log.debug("found " + elements.size() + " elements on page");

            Optional<WebElement> first = elements.stream()
                    .filter(webElement -> webElement.getText() != null && webElement.getText().startsWith(orderId))
                    .findFirst();
            if (first.isPresent()) {
                log.debug("found target order");

                first.get().findElements(By.xpath("td")).get(3).click();
                List<WebElement> inputList = new ArrayList<>();

                while (inputList.isEmpty()) {
                    waitCoupleOfSec(1);
                    inputList = driver.findElements(By.className("__input"));
                }

                WebElement input = inputList.get(0);
                for (String date : dates) {
                    input.sendKeys(date);
                    List<WebElement> busyBoxList = driver.findElements(By.className("busy-box_pending"));

                    while (!busyBoxList.isEmpty()){
                        waitCoupleOfSec(1);
                        busyBoxList = driver.findElements(By.className("busy-box_pending"));
                    }

                    if (!driver.findElements(By.className("vs__input")).isEmpty()) {
                        foundDates.add(date);
                        log.debug("found date: " + date);
                    }

                    input.clear();
                }

            } else {
                reply(session, event, "Не могу найти такого заказа: " + orderId);
                return;
            }

            if (foundDates.isEmpty()) {
                reply(session, event, "Для выбранных дат нет доставок :(");
            } else {
                reply(session, event, "Доступные даты: " + String.join(", ", foundDates));
                foundDates.clear();
            }
        } catch (Exception e) {
            if (e.getCause() != null && InvalidElementStateException.class.equals(e.getCause().getClass())) {
                log.error(e.getMessage());
                doCheck(session, event, driver);
            } else {
                log.error(e.getMessage());
            }
        }
    }

    public String getSlackToken() {
        return "xoxb-1122716004583-1137534900578-kkNwMIKc2oqqDX5UyAAb90pY";
    }

    public Bot getSlackBot() {
        return this;
    }

    private void waitCoupleOfSec(int sec) {
        try {
            Thread.sleep(sec * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
