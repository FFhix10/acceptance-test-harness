package org.jenkinsci.test.acceptance.po;

import javax.inject.Inject;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.hamcrest.StringDescription;
import org.jenkinsci.test.acceptance.junit.Resource;
import org.jenkinsci.test.acceptance.junit.Wait;
import org.jenkinsci.test.acceptance.utils.ElasticTime;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import com.google.common.base.Joiner;
import com.google.inject.Injector;

import static java.util.Arrays.*;

/**
 * For assisting porting from Capybara.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("CdiManagedBeanInconsistencyInspection")
public class CapybaraPortingLayerImpl implements CapybaraPortingLayer {
    /**
     * {@link org.openqa.selenium.WebDriver} that subtypes use to talk to the server.
     */
    @Inject
    protected WebDriver driver;

    /**
     * Access to the rest of the world.
     */
    @Inject
    public Injector injector;

    @Inject
    protected ElasticTime time;

    /**
     * Some subtypes are constructed via Guice, in which case injection is done by outside this class.
     * The injector parameter should be null for that case.
     * <p/>
     * Some subtypes are constructed programmatically. In that case, non-null injector must be supplied.
     */
    public CapybaraPortingLayerImpl(Injector injector) {
        this.injector = injector;
        if (injector != null) {
            injector.injectMembers(this);
        }
    }

    /**
     * Get a string representing the current URL that the browser is looking at.
     *
     * @return The URL of the page currently loaded in the browser
     */
    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    /**
     * Get a string representing the current URL with fragment part that the browser is looking at.
     *
     * @return The URL with fragment part of the page currently loaded in the browser.
     */
    public String getCurrentUrlWithFragment() {
        return executeScript("return document.location.href").toString();
    }

    /**
     * Navigates the browser to the page.
     *
     * @param url URL relative to the context path of Jenkins, such as "/about" or "/job/foo/configure".
     */
    protected final WebDriver visit(URL url) {
        driver.get(url.toExternalForm());
        return driver;
    }

    @Override
    public void clickButton(String text) {
        WebElement e = find(by.button(text));
        e.click();
    }

    /**
     * Select radio button by its name, id, or label text.
     */
    @Override
    public WebElement choose(String locator) {
        WebElement e = find(by.radioButton(locator));
        e.click();
        return e;
    }

    /**
     * Default waiting object configured with default timing.
     *
     * @see Wait
     */
    @Override
    public <T> Wait<T> waitFor(T subject) {
        return new Wait<>(subject, time)
                .pollingEvery(500, TimeUnit.MILLISECONDS)
                .withTimeout(120, TimeUnit.SECONDS)
        ;
    }

    @Override
    public Wait<CapybaraPortingLayer> waitFor() {
        return waitFor(this);
    }

    /**
     * Wait until the element that matches the given selector appears.
     */
    @Override
    public WebElement waitFor(final By selector, final int timeoutSec) {
        return waitFor(this).withMessage("Element matching %s is present", selector)
                .withTimeout(timeoutSec, TimeUnit.SECONDS)
                .ignoring(NoSuchElementException.class)
                .until(() -> find(selector));
    }

    @Override
    public WebElement waitFor(final By selector) {
        return waitFor(this).withMessage("Element matching %s is present", selector)
                .ignoring(NoSuchElementException.class)
                .until(() -> find(selector));
    }

    /**
     * Repeated evaluate the given predicate until it returns true.
     * <p/>
     * If it times out, an exception will be thrown.
     */
    @Override @Deprecated
    public <T> T waitForCond(Callable<T> block, int timeoutSec) {
        return waitFor(this).withTimeout(timeoutSec, TimeUnit.SECONDS).until(block);
    }

    @Override @Deprecated
    public <T> T waitForCond(Callable<T> block) {
        return waitFor(this).until(block);
    }

    @Override
    public <MatcherT, SubjectT extends MatcherT> void waitFor(SubjectT item, org.hamcrest.Matcher<MatcherT> matcher, final int timeout) {
        StringDescription desc = new StringDescription();
        matcher.describeTo(desc);
        waitFor(item).withMessage(desc.toString())
                .withTimeout(timeout, TimeUnit.SECONDS)
                .until(matcher)
        ;
    }

    /**
     * Returns the first visible element that matches the selector.
     *
     * @throws org.openqa.selenium.NoSuchElementException if the element is not found.
     * @see #getElement(org.openqa.selenium.By)         if you don't want to see an exception
     */
    @Override
    public WebElement find(final By selector) {
        try {
            return waitFor().withTimeout(time.seconds(1), TimeUnit.MILLISECONDS).until(new Callable<WebElement>() {
                @Override public WebElement call() {
                    for (WebElement element : driver.findElements(selector)) {
                        if (isDisplayed(element)) return element;
                    }
                    return null;
                }

                @Override public String toString() {
                    return "Wait for the element (" + selector + ") to become visible";
                }
            });
        } catch (NoSuchElementException|TimeoutException x) {
            // this is often the best place to set a breakpoint
            // Page url is not resent in otherwise verbose message
            String msg = String.format("Unable to locate %s in %s", selector, driver.getCurrentUrl());
            throw new NoSuchElementException(msg, x);
        }
    }

    /**
     * Returns the first element that matches the selector even if not visible.
     *
     * @throws org.openqa.selenium.NoSuchElementException if the element is not found.
     * @see #getElement(org.openqa.selenium.By)         if you don't want to see an exception
     */
    @Override
    public WebElement findIfNotVisible(By selector) {
        try {
            return driver.findElement(selector);
        } catch (NoSuchElementException x) {
            // this is often the best place to set a breakpoint
            // Page url is not resent in otherwise verbose message
            String msg = String.format("Unable to locate %s in %s", selector, driver.getCurrentUrl());
            throw new NoSuchElementException(msg, x);
        }
    }

    /**
     * Consider stale elements not displayed.
     */
    private boolean isDisplayed(WebElement e) {
        try {
            return e.isDisplayed();
        } catch (StaleElementReferenceException ignored) {
            return false;
        }
    }

    /**
     * Works like {@link #find(org.openqa.selenium.By)} but instead of throwing an exception,
     * this method returns null.
     */
    @Override
    public WebElement getElement(By selector) {
        // use all so that the breakpoint in find() method stays useful
        List<WebElement> all = all(selector);
        if (all.isEmpty()) {
            return null;
        }
        return all.get(0);
    }

    @Override
    public void fillIn(String formFieldName, Object value) {
        WebElement e = waitFor(by.name(formFieldName));
        e.clear();
        e.sendKeys(value.toString());
    }

    /**
     * Checks the checkbox.
     */
    @Override
    public void check(WebElement e) {
        check(e, true);
    }

    /**
     * Sets the state of the checkbox to the specified value.
     */
    @Override
    public void check(WebElement e, boolean state) {
        if (e.isSelected() != state) {
            try {
                e.click();
            } catch (ElementClickInterceptedException ex) {
                // tooltip can make an element not clickable.
                executeScript("arguments[0].click();", e);
            }
        }
        // It seems like Selenium sometimes has issues when trying to click elements that are out of view.
        // We use the following javascript as a workaround if the previous click failed.
        if (e.isSelected() != state) {
            executeScript("arguments[0].click();", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void blur(WebElement e) {
        executeScript(
            "var obj = arguments[0];"
            + "var ev = document.createEvent('MouseEvents');"
            + "ev.initEvent('blur', true, false);"
            + "obj.dispatchEvent(ev);"
            + "return true;", e);
    }

    /**
     * Finds all the elements that match the selector.
     * <p/>
     * <p/>
     * Note that this method inherits the same restriction of the {@link org.openqa.selenium.WebDriver#findElements(org.openqa.selenium.By)},
     * in that its execution is not synchronized with the JavaScript execution of the browser.
     * <p/>
     * <p/>
     * For example, if you click something that's expected to populate additional DOM elements,
     * and then call {@code all()} to find them, then all() can execute before those additional DOM elements
     * are populated, thereby failing to find the elements you are looking for.
     * <p/>
     * <p/>
     * In contrast, {@link #find(org.openqa.selenium.By)} do not have this problem, because it waits until the element
     * that matches the criteria appears.
     * <p/>
     * <p/>
     * So if you are using this method, think carefully. Perhaps you can use {@link #find(org.openqa.selenium.By)} to
     * achieve what you are looking for (by making the query more specific), or perhaps you can combine
     * this with {@link #waitForCond(java.util.concurrent.Callable)} so that if you don't find the elements you are looking for
     * in the list, you'll retry.
     */
    @Override
    public List<WebElement> all(By selector) {
        return driver.findElements(selector);
    }

    /**
     * Picks up the last visible element that matches given selector.
     */
    @Override
    public WebElement last(By selector) {
        find(selector); // wait until at least one is found

        // but what we want is the last one
        List<WebElement> l = driver.findElements(selector);
        return l.get(l.size() - 1);
    }

    /**
     * Picks up the last visible element that matches given selector.
     */
    @Override
    public WebElement lastIfNotVisible(By selector) {
        findIfNotVisible(selector); // wait until at least one is found

        // but what we want is the last one
        List<WebElement> l = driver.findElements(selector);
        return l.get(l.size() - 1);
    }

    /**
     * Returns {@code true} if the webElement is stale (that is accessing it would cause a
     * @{link StaleElementReferenceException} to be thrown
     * @param element the element to check.
     * @return {@code true} iff the element is stale.
     */
    protected static boolean isStale(WebElement element) {
        try {
            // Calling any method forces a staleness check
            element.isEnabled();
            return false;
        } catch (StaleElementReferenceException expected) {
            return true;
        }
    }

    /**
     * Executes JavaScript.
     */
    @Override
    public Object executeScript(String javaScript, Object... args) {
        return ((JavascriptExecutor) driver).executeScript(javaScript, args);
    }

    /**
     * @param locator Text, ID, or link.
     */
    @Override
    public void clickLink(String locator) {
        find(by.link(locator)).click();
    }

    /**
     * Checks the specified checkbox.
     */
    @Override
    public void check(String locator) {
        check(find(by.checkbox(locator)));
    }

    public void handleAlert(Consumer<Alert> action) {
        runThenHandleAlert(null, action);
    }

    public void runThenHandleAlert(Runnable runnable, Consumer<Alert> action) {
        runThenHandleAlert(runnable, action, 10);
    }

    public void runThenHandleAlert(Runnable runnable, Consumer<Alert> action, int timeoutSeconds) {
        String oldWindow = driver.getWindowHandle();
        if (runnable != null) {
            runnable.run();
        }
        Wait<WebDriver> wait = new Wait<>(driver, time)
                .pollingEvery(500, TimeUnit.MILLISECONDS)
                .withTimeout(timeoutSeconds, TimeUnit.SECONDS)
        ;
        Alert alert = wait.until(ExpectedConditions.alertIsPresent());
        try {
            action.accept(alert);
        } finally {
            driver.switchTo().window(oldWindow);
        }
    }

    @Override
    public void confirmAlert(int timeout) {
        runThenHandleAlert(null, Alert::accept, timeout);
    }

    public void runThenConfirmAlert(Runnable runnable) {
        runThenHandleAlert(runnable, Alert::accept);
    }

    @Override
    public void runThenConfirmAlert(Runnable runnable, int timeout) {
        runThenHandleAlert(runnable, Alert::accept, timeout);
    }

    /**
     * Thread.sleep that masks exception.
     */
    public void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new Error(e);
        }
    }

    public void elasticSleep(long ms) {
        sleep(time.milliseconds(ms));
    }

    /**
     * Finds matching constructor and invoke it.
     * <p/>
     * This is often useful for binding {@link org.jenkinsci.test.acceptance.po.PageArea} by taking the concrete type as a parameter.
     */
    protected <T> T newInstance(Class<T> type, Object... args) {
        try {
            OUTER:
            for (Constructor<?> c : type.getConstructors()) {
                Class<?>[] pts = c.getParameterTypes();
                if (pts.length != args.length) {
                    continue;
                }
                for (int i = 0; i < pts.length; i++) {
                    if (args[i] != null && !pts[i].isInstance(args[i])) {
                        continue OUTER;
                    }
                }

                return type.cast(c.newInstance(args));
            }

            throw new AssertionError("No matching constructor found in " + type + ": " + asList(args));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke a constructor of " + type, e);
        }
    }

    protected <T> T findCaption(Class<?> type, Finder<T> call) {
        String[] captions = type.getAnnotation(Describable.class).value();

        RuntimeException cause = new NoSuchElementException(
                "None of the captions exists: " + Joiner.on(", ").join(captions)
        );
        for (String caption : captions) {
            try {
                T out = call.find(caption);
                if (out != null) {
                    return out;
                }
            } catch (RuntimeException ex) {
                cause = ex;
            }
        }

        throw cause;
    }

    public static String getPageSource(WebDriver driver) {
        return (String) ((JavascriptExecutor) driver).executeScript(
                "return document.getElementsByTagName('html')[0].outerHTML"
        );
    }

    public String getPageSource() {
        return getPageSource(driver);
    }

    /**
     * Get visible text on the page.
     */
    public static String getPageContent(WebDriver driver) {
        return driver.findElement(by.css("html")).getText();
    }

    /**
     * Obtains a resource in a wrapper.
     */
    public Resource resource(String path) {
        final URL resource = getClass().getResource(path);
        if (resource == null) {
            throw new AssertionError("No such resource " + path + " for " + getClass().getName());
        }
        return new Resource(resource);
    }

    protected abstract class Finder<R> {
        protected final CapybaraPortingLayer outer = CapybaraPortingLayerImpl.this;

        protected abstract R find(String caption);
    }

    protected abstract class Resolver extends Finder<Object> {
        @Override
        protected final Object find(String caption) {
            resolve(caption);
            return this;
        }

        protected abstract void resolve(String caption);
    }
}
