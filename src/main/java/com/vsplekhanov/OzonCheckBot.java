package com.vsplekhanov;

import me.ramswaroop.jbot.core.common.Controller;
import me.ramswaroop.jbot.core.common.EventType;
import me.ramswaroop.jbot.core.common.JBot;
import me.ramswaroop.jbot.core.slack.Bot;
import me.ramswaroop.jbot.core.slack.models.Event;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
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
    private static final String STOP = "стоп";
    private static final String HEADLESS_OFF = "показать";
    private static final String HEADLESS_ON = "скрыть";
    private static final String PASS = "********";
    private static final String LOGIN = "***********";
    private static final int MILLIS_TO_SLEEP = 5000;
    private static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private volatile boolean headless = true;
    private String orderId;
    private String startDate;
    private int count;
    private int period;
    private List<String> dates;
    private volatile boolean running;

    private void checkExit(WebSocketSession session, Event event){
        if (STOP.equals(event.getText())) {
            reply(session, event, "Завершаю работу. Пока!");
            System.exit(0);
        }
    }

    @Controller(pattern = HEADLESS_ON, events = EventType.DIRECT_MESSAGE)
    public void hide(WebSocketSession session, Event event) {
        checkExit(session, event);
        headless = true;
        reply(session, event, "Скрытный режим активирован");
    }

    @Controller(pattern = HEADLESS_OFF, events = EventType.DIRECT_MESSAGE)
    public void show(WebSocketSession session, Event event) {
        checkExit(session, event);
        headless = false;
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
            reply(session, event, "Я не могу проверять 2 заказа одновременно");
            stopConversation(event);
        } else {
            running = true;
            startConversation(event, "getOrderId");   // start conversation
            reply(session, event, "Привет, какой номер заказа нужно проверить?");
        }
    }



    @Controller(next = "getStartDate", events = EventType.DIRECT_MESSAGE, pattern = "\\d+")
    public void getOrderId(WebSocketSession session, Event event) {
        checkExit(session, event);
        if (event.getText().matches("\\d+$")) {
            orderId = event.getText().trim();
            reply(session, event, "Отлично, с какого числа начать проверять? (через точку, например 01.01.2021)");
            nextConversation(event);
        } else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getOrderId");
        }
    }

    @Controller(next = "getEndDate", events = EventType.DIRECT_MESSAGE, pattern = "\\d{2}.\\d{2}.\\d{4}$")
    public void getStartDate(WebSocketSession session, Event event) {
        checkExit(session, event);
        if (event.getText().matches("\\d{2}.\\d{2}.\\d{4}$")){
            reply(session, event, "Хорошо, до какого числа проверять? (максимум 90 дней)");
            startDate = event.getText().trim();
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
            reply(session, event, "Выбран период в " + duration + " дней.\nСколко раз повторять проверку?");
            dates = IntStream.iterate(0, i -> i + 1)
                    .limit(duration + 1)
                    .mapToObj(date1::plusDays)
                    .map(date -> date.format(FORMATTER))
                    .collect(Collectors.toList());

            nextConversation(event);
        }  else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getEndDate");
        }
    }

    @Controller(next = "getPeriod", events = EventType.DIRECT_MESSAGE, pattern = "\\d+$")
    public void getCount(WebSocketSession session, Event event) {
        checkExit(session, event);

        if (event.getText().matches("\\d+$")) {
            count = Integer.parseInt(event.getText().trim());
            if (count == 1){
                startCheckLoop(session, event);
            } else if (count < 0) {
                reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
                nextConversation("getCount");
            } else {
                reply(session, event, "Принято, как часто проверять? (сколько минут между проверками?)");
                nextConversation(event);
            }
        } else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getCount");
        }
    }

    private void startCheckLoop(WebSocketSession session, Event event){
        reply(session, event, "Окей, начинаю проверку.\nЧтобы завершить напиши \"стоп\"");

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

            driver.get("https://seller.ozon.ru/signin");
            WebElement userName = driver.findElement(By.name("userName"));
            userName.sendKeys(LOGIN);
            WebElement password = driver.findElement(By.name("password"));
            password.sendKeys(PASS);
            password.sendKeys(Keys.ENTER);

            waitCopleSec();
            driver.get("https://seller.ozon.ru/supply/orders?tab=approved");
            waitCopleSec();

            List<String> foundDates = new ArrayList<>();

            List<WebElement> elements = driver.findElements(By.xpath("/html/body/div/div/main/div/div/div/div/div/div/div/div/div/div/div/div/table/tbody/tr"));
            Optional<WebElement> first = elements.stream()
                    .filter(webElement -> webElement.getText() != null && webElement.getText().startsWith(orderId))
                    .findFirst();
            if (first.isPresent()) {
                first.get().findElements(By.xpath("td")).get(3).click();
                WebElement input = driver.findElement(By.className("__input"));
                if (input != null) {
                    for (String date : dates) {
                        input.sendKeys(date);
                        waitCopleSec();

                        if (!driver.findElements(By.className("vs__input")).isEmpty()) {
                            foundDates.add(date);
                        }
                        input.clear();
                    }
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
        }finally {
            if (driver != null) {
                driver.close();
            }
        }
    }

    public String getSlackToken() {
        return "xoxb-1122716004583-1137534900578-7oNtBbTcrEAk4E1Mgvms7EFy";
    }

    public Bot getSlackBot() {
        return this;
    }

    private void waitCopleSec() {
        try {
            Thread.sleep(MILLIS_TO_SLEEP);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
