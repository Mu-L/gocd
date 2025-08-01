require_relative "boot"

require "rails"
# Pick the frameworks you want:
require "active_model/railtie"
require "action_controller/railtie"
require "action_view/railtie"
require "sprockets/railtie"
require "rails/test_unit/railtie"

# Require the gems listed in Gemfile, including any gems
# you've limited to :test, :development, or :production.
Bundler.require(*Rails.groups(assets: %w[development test]))

module Go
  class Application < Rails::Application
    config.load_defaults 7.2

    # Load extensions which cannot be auto-loaded
    require_relative '../lib/extensions/case_insensitive_string'
    require_relative '../lib/extensions/java_util_date'
    require_relative '../lib/extensions/route_ext'

    # Please, add to the `ignore` list any other `lib` subdirectories that do
    # not contain `.rb` files, or that should not be reloaded or eager loaded.
    # Common ones are `templates`, `generators`, or `middleware`, for example.
    config.autoload_lib(ignore: %w[assets tasks extensions])

    # Configuration for the application, engines, and railties goes here.
    #
    # These settings can be overridden in specific environments using the files
    # in config/environments, which are processed later.
    #
    # config.time_zone = "Central Time (US & Canada)"
    # config.eager_load_paths << Rails.root.join("extras")

    # Rails does not load lib/* by default. Forcing it to do so.
    config.autoload_paths += Dir[
      Rails.root.join("lib"),
      Rails.root.join("app", "models"),
      Rails.root.join("app", "presenters")
    ]

    # Add catch-all route, after all Rails routes and Engine routes are initialized.
    initializer :add_catch_all_route, :after => :add_routing_paths do |app|
      app.routes.append do
        match "*url", via: :all, to: "application#unresolved"
      end
    end

    require Rails.root.join("lib", "slf4j_logger.rb")
    config.logger = Slf4jLogger::Logger.new("com.thoughtworks.go.server.Rails")

    config.generators do |g|
      g.test_framework        :rspec, :fixture_replacement => nil
    end

    config.action_controller.include_all_helpers  = true
    config.action_controller.per_form_csrf_tokens = false

    # Disable default headers in rails, since they are added from other filters like DefaultHeadersFilter
    config.action_dispatch.default_headers = {}

    # Also see css-loader.ts for similar config for Webpack
    if config.respond_to?(:sass)
      config.sass.quiet_deps = true # Mainly noise from FontAwesome, Foundation Sites and/or Bourbon left
      config.sass.silence_deprecations = [
        'import', # Can't do much about this until FontAwesome updates
      ]
    end
  end
end
