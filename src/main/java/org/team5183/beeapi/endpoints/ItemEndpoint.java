package org.team5183.beeapi.endpoints;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.team5183.beeapi.constants.Permission;
import org.team5183.beeapi.entities.CheckoutEntity;
import org.team5183.beeapi.entities.ItemEntity;
import org.team5183.beeapi.middleware.Authentication;
import org.team5183.beeapi.response.BasicResponse;
import org.team5183.beeapi.response.ResponseStatus;
import spark.Filter;
import spark.Request;
import spark.Response;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static spark.Spark.*;

public class ItemEndpoint extends Endpoint {
    private static final Logger logger = LogManager.getLogger(ItemEndpoint.class);

    @Override
    public void registerEndpoints() {
        path("/items" , () -> {
            before("*", this::authenticate);
            get("/all", this::getAllItems);
            get("/all?limit=:limit", this::getAllItems);
            get("/all?offset=:offset", this::getAllItems);
            get("/all?limit=:limit&offset=:offset", this::getAllItems);

            path("/:id", () -> {
                before("", this::isItemExist);

                get("", this::endpointGetItem);

                delete("", this::deleteItem);

                patch("", "application/json", this::updateItem);

                path("/checkout", () -> {
                    get("/active", this::getItemActiveCheckout);

                    get("/all", this::getAllItemCheckouts);

                    path("/:checkoutId", () -> {
                        get("", this::getItemCheckout);
//                        patch("", this::updateItemCheckout);
//                        delete("", this::deleteItemCheckout);
                    });
                });

                post("/checkout", this::checkoutItem);
                patch("/return", this::returnItem);
            });

            post("/new", "application/json", this::newItem);
        });
    }

    private Filter isItemExist(Request req, Response res) {
        if (req.params(":id").equals("all") || req.params(":id").equals("new")) return null;

        if (req.params(":id").isEmpty()) end(400, ResponseStatus.ERROR, "Missing ID");

        try {
            Long.parseLong(req.params(":id"));
        } catch (NumberFormatException e) {
            end(400, ResponseStatus.ERROR, "ID must be a number");
        }

        try {
            if (ItemEntity.getItemEntity(Long.parseLong(req.params(":id"))) == null)
                end(404, ResponseStatus.ERROR, "Item with ID " + req.params(":id") + " not found");
        } catch (SQLException e) {
            e.printStackTrace();
            end(500, ResponseStatus.ERROR, "Internal Server Error");
        }

        return null;
    }

    private ItemEntity getItem(Request req, Response res) {
        before("", this::isItemExist);

        try {
            return ItemEntity.getItemEntity(Long.parseLong(req.params(":id")));
        } catch (SQLException e) {
            e.printStackTrace();
            end(500, ResponseStatus.ERROR, "Internal Server Error");
        }

        return null;
    }

    private String getAllItems(Request req, Response res) {
        before("", this.checkPermission(req, res, Permission.CAN_VIEW_ITEMS));

        List<ItemEntity> items = null;
        try {
            items = ItemEntity.getAllItemEntities();
        } catch (SQLException e) {
            e.printStackTrace();
            end(500, ResponseStatus.ERROR, "Internal Server Error");
        }
        if (items == null) end(200, ResponseStatus.SUCCESS, gson.toJsonTree(items));
        assert items != null;

        // TODO: add a method to call offset or limit directly?
        if (req.params(":offset") != null && !req.params(":offset").isEmpty()) items = items.subList(Integer.parseInt(req.params(":offset")), items.size());

        if (req.params(":limit") != null && !req.params(":limit").isEmpty()) {
            List<ItemEntity> itemsCopy = new ArrayList<>(Integer.parseInt(req.params(":limit")));
            for (int i = 0; i < items.size(); i++) {
                itemsCopy.add(items.get(i));
                if (i >= Integer.parseInt(req.params(":limit"))) {
                    break;
                }
            }
            return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, gson.toJsonTree(itemsCopy)));
        }
        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, gson.toJsonTree(items)));
    }

    private String endpointGetItem(Request req, Response res) {
        before("", this.checkPermission(req, res, Permission.CAN_VIEW_ITEMS));
        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, gson.toJsonTree(getItem(req, res))));
    }

    private String deleteItem(Request req, Response res) {
        before("", this.checkPermission(req, res, Permission.CAN_DELETE_ITEMS));
        before("", this::isItemExist);
        try {
            ItemEntity item = ItemEntity.getItemEntity(Long.parseLong(req.params(":id")));
            if (item == null) end(404, ResponseStatus.ERROR, "Item with ID " + req.params(":id") + " not found");
            assert item != null;
            item.delete();
        } catch (SQLException e) {
            e.printStackTrace();
            end(500, ResponseStatus.ERROR, "Internal Server Error");
        }

        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, "Deleted Item with ID " + req.params(":id")));
    }

    private String updateItem(Request req, Response res) {
        before("", this.checkPermission(req, res, Permission.CAN_EDIT_ITEMS));
        before("", this::isItemExist);
        ItemEntity item = this.objectFromBody(req, ItemEntity.class);

        try {
            item.update();
        } catch (SQLException e) {
            e.printStackTrace();
            end(500, ResponseStatus.ERROR, "Internal Server Error");
        }

        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, "Updated Item with ID " + req.params(":id"), gson.toJsonTree(item)));
    }

    private CheckoutEntity getCheckout(Request req, Response res) {
        before("", this.checkPermission(req, res, Permission.CAN_VIEW_CHECKOUTS));
        ItemEntity item = getItem(req, res);
        assert item != null;

        CheckoutEntity checkout = null;
        try {
            checkout = item.getCheckoutEntities().get(Long.parseLong(req.params(":checkoutId")));
        } catch (NumberFormatException e) {
            end(400, ResponseStatus.ERROR, "Invalid checkout ID");
        }

        if (checkout == null) {
            end(404, ResponseStatus.ERROR, "Checkout with ID " + req.params(":checkoutId") + " not found");
        }

        return checkout;
    }

    private String getItemActiveCheckout(Request req, Response res) {
        before("", this.checkPermission(req, res, Permission.CAN_VIEW_CHECKOUTS));
        ItemEntity item = getItem(req, res);
        assert item != null;

        if (item.getCheckoutEntity() == null) {
            end(404, ResponseStatus.ERROR, "Item with ID " + req.params(":id") + " is not checked out");
        }

        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, gson.toJsonTree(item.getCheckoutEntity())));
    }

    private String getAllItemCheckouts(Request req, Response res) {
        before("", this.checkPermission(req, res, Permission.CAN_VIEW_CHECKOUTS));
        ItemEntity item = getItem(req, res);
        assert item != null;

        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, gson.toJsonTree(item.getCheckoutEntities())));
    }

    private String checkoutItem(Request req, Response res) {
        before("", Authentication.checkPermission(req, res, Permission.CAN_CHECKOUT_ITEMS));
        ItemEntity item = getItem(req, res);
        assert item != null;

        CheckoutEntity checkout = objectFromBody(req, CheckoutEntity.class);
        assert checkout != null;

        checkout.setActive(true);

        item.setCheckoutEntity(checkout);
        try {
            item.update();
        } catch (SQLException e) {
            end(500, ResponseStatus.ERROR, "Internal Server Error");
        }

        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, "Checked out Item with ID " + req.params(":id")));
    }

    private String getItemCheckout(Request req, Response res) {
        before("", Authentication.checkPermission(req, res, Permission.CAN_VIEW_CHECKOUTS));
        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, gson.toJsonTree(getCheckout(req, res))));
    }

    private String returnItem(Request req, Response res) {
        before("", Authentication.checkPermission(req, res, Permission.CAN_RETURN_ITEMS));
        ItemEntity item = getItem(req, res);
        assert item != null;

        CheckoutEntity checkout = item.getCheckoutEntity();
        if (checkout == null) {
            end(404, ResponseStatus.ERROR, "Item with ID " + req.params(":id") + " is not checked out");
        }
        assert checkout != null;


        item.setCheckoutEntity(null);
        item.removeCheckoutEntity(checkout);
        checkout.setActive(false);
        checkout.setReturnDate(Instant.now().toEpochMilli());
        item.addCheckoutEntity(checkout);

        try {
            item.update();
        } catch (SQLException e) {
            end(500, ResponseStatus.ERROR, "Internal Server Error");
        }

        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, "Returned Item with ID " + req.params(":id")));
    }

    private String newItem(Request request, Response response) {
        before("", Authentication.checkPermission(request, response, Permission.CAN_CREATE_ITEMS));
        ItemEntity item = this.objectFromBody(request, ItemEntity.class);

        assert item != null;

        try {
            item.create();
        } catch (SQLException e) {
            e.printStackTrace();
            end(500, ResponseStatus.ERROR, "Internal Server Error");
        }

        response.status(201);
        return gson.toJson(new BasicResponse(ResponseStatus.SUCCESS, "Created item with ID "+ item.getId(), gson.toJsonTree(item)));
    }
}
