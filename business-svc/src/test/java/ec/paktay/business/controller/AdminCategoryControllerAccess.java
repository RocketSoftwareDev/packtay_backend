package ec.paktay.business.controller;

/** Expone a las pruebas de otro paquete la regla del código de subcategoría. */
public final class AdminCategoryControllerAccess {
    private AdminCategoryControllerAccess() {
    }

    public static String slug(String name) {
        return AdminCategoryController.slug(name);
    }
}
